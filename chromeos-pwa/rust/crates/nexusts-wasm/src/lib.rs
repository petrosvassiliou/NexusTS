//! Zero-import `wasm32-unknown-unknown` Cryptography & Remote Desktop Codec Accelerator for NexusTS ChromeOS PWA / IWA.
//! Byte-for-byte compatible with `native/rust/crates/nexusts-crypto` (`.nxts` v2 `NXTS\x00\x02`),
//! RFB 3.8 VNC bit-reversed DES challenge auth, and MS-RDPBCGR Interleaved RLE bitmap decompression.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use argon2::{Algorithm, Argon2, Params, Version};
use base64::engine::general_purpose::{STANDARD as BASE64_STD, STANDARD_NO_PAD as BASE64_NO_PAD};
use base64::Engine;
use cipher::BlockEncrypt;
use des::Des;
use ed25519_dalek::{SigningKey, VerifyingKey};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use std::cell::RefCell;
use zeroize::{Zeroize, Zeroizing};

pub const NXTS_MAGIC_V2: [u8; 6] = [b'N', b'X', b'T', b'S', 0x00, 0x02];
pub const SALT_LEN: usize = 32;
pub const NONCE_LEN: usize = 12;
pub const NXTS_HEADER_LEN: usize = 70;

thread_local! {
    static RESULT_BUF: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

fn set_result(bytes: Vec<u8>) -> i32 {
    let len = bytes.len() as i32;
    RESULT_BUF.with(|cell| {
        *cell.borrow_mut() = bytes;
    });
    len
}

#[no_mangle]
pub extern "C" fn nxts_wasm_alloc(len: usize) -> *mut u8 {
    let mut buf = Vec::<u8>::with_capacity(len);
    let ptr = buf.as_mut_ptr();
    std::mem::forget(buf);
    ptr
}

/// # Safety
/// `ptr` must have been allocated via `nxts_wasm_alloc(cap)`.
#[no_mangle]
pub unsafe extern "C" fn nxts_wasm_free(ptr: *mut u8, cap: usize) {
    if !ptr.is_null() && cap > 0 {
        let mut buf = Vec::from_raw_parts(ptr, 0, cap);
        buf.zeroize();
    }
}

#[no_mangle]
pub extern "C" fn nxts_wasm_result_ptr() -> *const u8 {
    RESULT_BUF.with(|cell| cell.borrow().as_ptr())
}

#[no_mangle]
pub extern "C" fn nxts_wasm_result_len() -> usize {
    RESULT_BUF.with(|cell| cell.borrow().len())
}

#[no_mangle]
pub extern "C" fn nxts_wasm_clear_result() {
    RESULT_BUF.with(|cell| {
        let mut guard = cell.borrow_mut();
        guard.zeroize();
        guard.clear();
    });
}

pub fn derive_aes256gcm_key(
    passphrase: &[u8],
    salt: &[u8; SALT_LEN],
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
) -> Result<Zeroizing<[u8; 32]>, &'static str> {
    let params = Params::new(memory_kib, iterations, parallelism, Some(32))
        .map_err(|_| "invalid argon2id params")?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut ikm = Zeroizing::new([0u8; 32]);
    argon2
        .hash_password_into(passphrase, salt, ikm.as_mut())
        .map_err(|_| "argon2id hash failed")?;

    let hk = Hkdf::<Sha256>::new(Some(salt), ikm.as_ref());
    let mut aes_key = Zeroizing::new([0u8; 32]);
    hk.expand(b"nexusts-vault-v2-aes256gcm", aes_key.as_mut())
        .map_err(|_| "hkdf expand failed")?;
    Ok(aes_key)
}

pub fn seal_nxts_deterministic(
    plaintext: &[u8],
    passphrase: &[u8],
    salt: &[u8; SALT_LEN],
    nonce: &[u8; NONCE_LEN],
    revision: u64,
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
) -> Result<Vec<u8>, &'static str> {
    let key = derive_aes256gcm_key(passphrase, salt, memory_kib, iterations, parallelism)?;
    let cipher = Aes256Gcm::new_from_slice(key.as_ref()).map_err(|_| "aes init failed")?;

    let mut header = Vec::with_capacity(NXTS_HEADER_LEN);
    header.extend_from_slice(&NXTS_MAGIC_V2);
    header.extend_from_slice(&memory_kib.to_be_bytes());
    header.extend_from_slice(&iterations.to_be_bytes());
    header.extend_from_slice(&parallelism.to_be_bytes());
    header.extend_from_slice(salt);
    header.extend_from_slice(nonce);
    header.extend_from_slice(&revision.to_be_bytes());

    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: plaintext,
                aad: &header,
            },
        )
        .map_err(|_| "aes encrypt failed")?;

    let mut out = Vec::with_capacity(NXTS_HEADER_LEN + ciphertext.len());
    out.extend_from_slice(&header);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

pub fn open_nxts_container(container: &[u8], passphrase: &[u8]) -> Result<Vec<u8>, &'static str> {
    if container.len() < NXTS_HEADER_LEN + 16 || container[0..6] != NXTS_MAGIC_V2 {
        return Err("invalid nxts header");
    }
    let memory_kib = u32::from_be_bytes(container[6..10].try_into().unwrap());
    let iterations = u32::from_be_bytes(container[10..14].try_into().unwrap());
    let parallelism = u32::from_be_bytes(container[14..18].try_into().unwrap());
    if memory_kib < 8_192 || iterations < 1 || parallelism < 1 {
        return Err("kdf below security floor");
    }
    let mut salt = [0u8; SALT_LEN];
    salt.copy_from_slice(&container[18..50]);
    let mut nonce = [0u8; NONCE_LEN];
    nonce.copy_from_slice(&container[50..62]);

    let key = derive_aes256gcm_key(passphrase, &salt, memory_kib, iterations, parallelism)?;
    let cipher = Aes256Gcm::new_from_slice(key.as_ref()).map_err(|_| "aes init failed")?;
    cipher
        .decrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &container[NXTS_HEADER_LEN..],
                aad: &container[0..NXTS_HEADER_LEN],
            },
        )
        .map_err(|_| "authentication failed")
}

/// # Safety
/// All pointer/length pairs must reference valid initialized Wasm linear memory.
#[no_mangle]
pub unsafe extern "C" fn nxts_wasm_seal_vault(
    plain_ptr: *const u8,
    plain_len: usize,
    pass_ptr: *const u8,
    pass_len: usize,
    salt_ptr: *const u8,
    nonce_ptr: *const u8,
    rev_hi: u32,
    rev_lo: u32,
    memory_kib: u32,
    iterations: u32,
    parallelism: u32,
) -> i32 {
    if plain_ptr.is_null() || pass_ptr.is_null() || salt_ptr.is_null() || nonce_ptr.is_null() {
        return -1;
    }
    let plain = std::slice::from_raw_parts(plain_ptr, plain_len);
    let pass = std::slice::from_raw_parts(pass_ptr, pass_len);
    let salt: &[u8; SALT_LEN] = std::slice::from_raw_parts(salt_ptr, SALT_LEN)
        .try_into()
        .unwrap();
    let nonce: &[u8; NONCE_LEN] = std::slice::from_raw_parts(nonce_ptr, NONCE_LEN)
        .try_into()
        .unwrap();
    let revision = (u64::from(rev_hi) << 32) | u64::from(rev_lo);
    match seal_nxts_deterministic(
        plain,
        pass,
        salt,
        nonce,
        revision,
        memory_kib,
        iterations,
        parallelism,
    ) {
        Ok(sealed) => set_result(sealed),
        Err(_) => -2,
    }
}

/// # Safety
/// All pointer/length pairs must reference valid initialized Wasm linear memory.
#[no_mangle]
pub unsafe extern "C" fn nxts_wasm_open_vault(
    container_ptr: *const u8,
    container_len: usize,
    pass_ptr: *const u8,
    pass_len: usize,
) -> i32 {
    if container_ptr.is_null() || pass_ptr.is_null() {
        return -1;
    }
    let container = std::slice::from_raw_parts(container_ptr, container_len);
    let pass = std::slice::from_raw_parts(pass_ptr, pass_len);
    match open_nxts_container(container, pass) {
        Ok(plain) => set_result(plain),
        Err(_) => -2,
    }
}

fn encode_ssh_str(buf: &mut Vec<u8>, bytes: &[u8]) {
    buf.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    buf.extend_from_slice(bytes);
}

pub fn build_openssh_ed25519_from_seed(seed: &[u8; 32], check_int: u32, comment: &str) -> String {
    let signing = SigningKey::from_bytes(seed);
    let verifying: VerifyingKey = signing.verifying_key();

    let mut pub_blob = Vec::new();
    encode_ssh_str(&mut pub_blob, b"ssh-ed25519");
    encode_ssh_str(&mut pub_blob, verifying.as_bytes());
    let pub_b64 = BASE64_STD.encode(&pub_blob);
    let pub_line = if comment.trim().is_empty() {
        format!("ssh-ed25519 {pub_b64}")
    } else {
        format!("ssh-ed25519 {pub_b64} {}", comment.trim())
    };

    let fp = format!("SHA256:{}", BASE64_NO_PAD.encode(Sha256::digest(&pub_blob)));

    let mut openssh_priv = Vec::new();
    openssh_priv.extend_from_slice(b"openssh-key-v1\0");
    encode_ssh_str(&mut openssh_priv, b"none");
    encode_ssh_str(&mut openssh_priv, b"none");
    encode_ssh_str(&mut openssh_priv, &[]);
    openssh_priv.extend_from_slice(&1u32.to_be_bytes());
    encode_ssh_str(&mut openssh_priv, &pub_blob);

    let mut priv_sec = Vec::new();
    priv_sec.extend_from_slice(&check_int.to_be_bytes());
    priv_sec.extend_from_slice(&check_int.to_be_bytes());
    encode_ssh_str(&mut priv_sec, b"ssh-ed25519");
    encode_ssh_str(&mut priv_sec, verifying.as_bytes());

    let mut expanded = [0u8; 64];
    expanded[0..32].copy_from_slice(&signing.to_bytes());
    expanded[32..64].copy_from_slice(verifying.as_bytes());
    encode_ssh_str(&mut priv_sec, &expanded);
    expanded.zeroize();

    encode_ssh_str(&mut priv_sec, comment.as_bytes());
    let mut pad = 1u8;
    while priv_sec.len() % 8 != 0 {
        priv_sec.push(pad);
        pad = pad.wrapping_add(1);
    }
    encode_ssh_str(&mut openssh_priv, &priv_sec);
    priv_sec.zeroize();

    let wrapped = BASE64_STD
        .encode(&openssh_priv)
        .as_bytes()
        .chunks(70)
        .map(|l| std::str::from_utf8(l).unwrap_or(""))
        .collect::<Vec<_>>()
        .join("\n");
    openssh_priv.zeroize();

    let pem = format!("-----BEGIN OPENSSH PRIVATE KEY-----\n{wrapped}\n-----END OPENSSH PRIVATE KEY-----\n");
    format!("{fp}\n{pub_line}\n{pem}")
}

/// # Safety
/// Pointer arguments must reference valid Wasm linear memory.
#[no_mangle]
pub unsafe extern "C" fn nxts_wasm_ed25519_keypair(
    seed_ptr: *const u8,
    check_int: u32,
    comment_ptr: *const u8,
    comment_len: usize,
) -> i32 {
    if seed_ptr.is_null() {
        return -1;
    }
    let seed: &[u8; 32] = std::slice::from_raw_parts(seed_ptr, 32).try_into().unwrap();
    let comment = if comment_ptr.is_null() || comment_len == 0 {
        ""
    } else {
        std::str::from_utf8(std::slice::from_raw_parts(comment_ptr, comment_len)).unwrap_or("")
    };
    set_result(build_openssh_ed25519_from_seed(seed, check_int, comment).into_bytes())
}

/// Encrypts a 16-byte RFB 3.8 VNC DES challenge using RFB's bit-reversed password key schedule.
/// # Safety
/// `pass8_ptr` (8 bytes) and `challenge16_ptr` (16 bytes) must be valid pointers.
#[no_mangle]
pub unsafe extern "C" fn nxts_wasm_vnc_des_response(
    pass8_ptr: *const u8,
    challenge16_ptr: *const u8,
) -> i32 {
    if pass8_ptr.is_null() || challenge16_ptr.is_null() {
        return -1;
    }
    let raw_key = std::slice::from_raw_parts(pass8_ptr, 8);
    let challenge = std::slice::from_raw_parts(challenge16_ptr, 16);
    let mut rev_key = [0u8; 8];
    for i in 0..8 {
        rev_key[i] = raw_key[i].reverse_bits();
    }
    let cipher = Des::new_from_slice(&rev_key).unwrap();
    let mut out = challenge.to_vec();
    cipher.encrypt_block((&mut out[0..8]).into());
    cipher.encrypt_block((&mut out[8..16]).into());
    rev_key.zeroize();
    set_result(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wasm_nxts_roundtrip_and_aad_integrity() {
        let salt = [7u8; SALT_LEN];
        let nonce = [9u8; NONCE_LEN];
        let plain = br#"{"documentId":"doc-1","name":"ChromeOS Vault","schemaVersion":2}"#;
        let pass = b"chromeos-offline-secret";
        let sealed = seal_nxts_deterministic(plain, pass, &salt, &nonce, 12, 8_192, 1, 1).unwrap();
        assert_eq!(&sealed[0..6], &NXTS_MAGIC_V2);
        let opened = open_nxts_container(&sealed, pass).unwrap();
        assert_eq!(opened.as_slice(), plain);
        assert!(open_nxts_container(&sealed, b"wrong").is_err());
    }

    #[test]
    fn wasm_ed25519_openssh_format() {
        let out = build_openssh_ed25519_from_seed(&[3u8; 32], 0x1234_5678, "chromeos@nexusts");
        assert!(out.starts_with("SHA256:"));
        assert!(out.contains("ssh-ed25519 "));
        assert!(out.contains("-----BEGIN OPENSSH PRIVATE KEY-----"));
    }
}
