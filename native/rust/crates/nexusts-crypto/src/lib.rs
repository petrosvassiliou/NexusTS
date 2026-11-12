//! Zero-knowledge `.nxts` Vault Cryptography, Argon2id + AES-256-GCM Envelope,
//! HKDF Subkey Derivation, and OpenSSH Ed25519 Key Generator & In-Memory Agent.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use argon2::{Algorithm, Argon2, Params, Version};
use base64::engine::general_purpose::STANDARD as BASE64_STD;
use base64::Engine;
use ed25519_dalek::{Signer, SigningKey, VerifyingKey};
use hkdf::Hkdf;
use rand::rngs::OsRng;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use thiserror::Error;
use zeroize::{Zeroize, Zeroizing};

/// Binary magic header for `.nxts` Vault Format v2 (`NXTS\x00\x02`).
pub const NXTS_MAGIC_V2: [u8; 6] = [b'N', b'X', b'T', b'S', 0x00, 0x02];
pub const SALT_LEN: usize = 32;
pub const NONCE_LEN: usize = 12;
/// Header layout:
/// [0..6]   magic `NXTS\x00\x02`
/// [6..10]  argon2 m_cost_kib (u32 big-endian)
/// [10..14] argon2 t_cost (u32 big-endian)
/// [14..18] argon2 p_cost (u32 big-endian)
/// [18..50] 32-byte salt
/// [50..62] 12-byte AES-256-GCM nonce
/// [62..70] revision sequence (u64 big-endian, authenticated via GCM AAD)
/// [70..]   AES-256-GCM ciphertext + 16-byte authentication tag
pub const NXTS_HEADER_LEN: usize = 70;

#[derive(Debug, Error)]
pub enum CryptoError {
    #[error("Invalid .nxts vault header or unsupported format version")]
    InvalidVaultHeader,
    #[error("Argon2id key derivation parameter error: {0}")]
    InvalidKdfParameters(String),
    #[error("Vault authentication failed: wrong master passphrase or tampered ciphertext")]
    AuthenticationFailed,
    #[error("SSH key operation failed: {0}")]
    SshKeyError(String),
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct Argon2idProfile {
    pub memory_kib: u32,
    pub iterations: u32,
    pub parallelism: u32,
}

impl Default for Argon2idProfile {
    fn default() -> Self {
        Self {
            memory_kib: 65_536, // 64 MiB enterprise zero-knowledge default
            iterations: 3,
            parallelism: 4,
        }
    }
}

struct DerivedVaultKeys {
    aes_gcm_key: Zeroizing<[u8; 32]>,
}

fn derive_nxts_subkeys(
    master_secret: &[u8],
    salt: &[u8; SALT_LEN],
    profile: Argon2idProfile,
) -> Result<DerivedVaultKeys, CryptoError> {
    let params = Params::new(
        profile.memory_kib,
        profile.iterations,
        profile.parallelism,
        Some(32),
    )
    .map_err(|e| CryptoError::InvalidKdfParameters(e.to_string()))?;

    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut ikm = Zeroizing::new([0u8; 32]);
    argon2
        .hash_password_into(master_secret, salt, ikm.as_mut())
        .map_err(|e| CryptoError::InvalidKdfParameters(e.to_string()))?;

    let hk = Hkdf::<Sha256>::new(Some(salt), ikm.as_ref());
    let mut aes_gcm_key = Zeroizing::new([0u8; 32]);
    hk.expand(b"nexusts-vault-v2-aes256gcm", aes_gcm_key.as_mut())
        .map_err(|_| CryptoError::InvalidKdfParameters("HKDF expand failure".into()))?;

    Ok(DerivedVaultKeys { aes_gcm_key })
}

/// Seals UTF-8/CBOR `.nxts` document plaintext into an authenticated binary `.nxts` v2 container.
pub fn seal_nxts_vault(
    plaintext: &[u8],
    passphrase: &[u8],
    revision: u64,
    profile: Argon2idProfile,
) -> Result<Vec<u8>, CryptoError> {
    let mut salt = [0u8; SALT_LEN];
    let mut nonce_bytes = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut salt);
    OsRng.fill_bytes(&mut nonce_bytes);

    let keys = derive_nxts_subkeys(passphrase, &salt, profile)?;
    let cipher = Aes256Gcm::new_from_slice(keys.aes_gcm_key.as_ref())
        .map_err(|_| CryptoError::InvalidKdfParameters("Invalid AES-256 key length".into()))?;

    let mut header = Vec::with_capacity(NXTS_HEADER_LEN);
    header.extend_from_slice(&NXTS_MAGIC_V2);
    header.extend_from_slice(&profile.memory_kib.to_be_bytes());
    header.extend_from_slice(&profile.iterations.to_be_bytes());
    header.extend_from_slice(&profile.parallelism.to_be_bytes());
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce_bytes);
    header.extend_from_slice(&revision.to_be_bytes());
    debug_assert_eq!(header.len(), NXTS_HEADER_LEN);

    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce_bytes),
            Payload {
                msg: plaintext,
                aad: &header,
            },
        )
        .map_err(|_| CryptoError::AuthenticationFailed)?;

    let mut output = Vec::with_capacity(NXTS_HEADER_LEN + ciphertext.len());
    output.extend_from_slice(&header);
    output.extend_from_slice(&ciphertext);
    Ok(output)
}

#[derive(Debug)]
pub struct UnsealedVault {
    pub plaintext: Zeroizing<Vec<u8>>,
    pub revision: u64,
    pub kdf_profile: Argon2idProfile,
}

/// Verifies header AAD and decrypts a binary `.nxts` v2 container.
pub fn open_nxts_vault(
    container: &[u8],
    passphrase: &[u8],
) -> Result<UnsealedVault, CryptoError> {
    if container.len() < NXTS_HEADER_LEN + 16 {
        return Err(CryptoError::InvalidVaultHeader);
    }
    if container[0..6] != NXTS_MAGIC_V2 {
        return Err(CryptoError::InvalidVaultHeader);
    }

    let memory_kib = u32::from_be_bytes(container[6..10].try_into().unwrap());
    let iterations = u32::from_be_bytes(container[10..14].try_into().unwrap());
    let parallelism = u32::from_be_bytes(container[14..18].try_into().unwrap());

    if memory_kib < 8_192 || iterations < 1 || parallelism < 1 {
        return Err(CryptoError::InvalidKdfParameters(
            "KDF parameters below minimum security floor".into(),
        ));
    }

    let mut salt = [0u8; SALT_LEN];
    salt.copy_from_slice(&container[18..50]);

    let mut nonce_bytes = [0u8; NONCE_LEN];
    nonce_bytes.copy_from_slice(&container[50..62]);

    let revision = u64::from_be_bytes(container[62..70].try_into().unwrap());
    let profile = Argon2idProfile {
        memory_kib,
        iterations,
        parallelism,
    };

    let keys = derive_nxts_subkeys(passphrase, &salt, profile)?;
    let cipher = Aes256Gcm::new_from_slice(keys.aes_gcm_key.as_ref())
        .map_err(|_| CryptoError::InvalidKdfParameters("AES key init error".into()))?;

    let header = &container[0..NXTS_HEADER_LEN];
    let ciphertext = &container[NXTS_HEADER_LEN..];

    let decrypted = cipher
        .decrypt(
            Nonce::from_slice(&nonce_bytes),
            Payload {
                msg: ciphertext,
                aad: header,
            },
        )
        .map_err(|_| CryptoError::AuthenticationFailed)?;

    Ok(UnsealedVault {
        plaintext: Zeroizing::new(decrypted),
        revision,
        kdf_profile: profile,
    })
}

/// Generated OpenSSH Ed25519 Keypair with SHA-256 fingerprint.
#[derive(Debug, Serialize, Deserialize)]
pub struct GeneratedSshKeypair {
    pub key_id: String,
    pub public_key_openssh: String,
    pub private_key_openssh_pem: Zeroizing<String>,
    pub sha256_fingerprint: String,
}

fn encode_ssh_string(buf: &mut Vec<u8>, bytes: &[u8]) {
    buf.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    buf.extend_from_slice(bytes);
}

/// Generates a fresh Ed25519 SSH keypair formatted in standard OpenSSH wire & PEM format.
pub fn generate_ed25519_ssh_keypair(comment: &str) -> GeneratedSshKeypair {
    let signing_key = SigningKey::generate(&mut OsRng);
    let verifying_key: VerifyingKey = signing_key.verifying_key();

    // Wire format for ssh-ed25519 public key blob
    let mut pub_blob = Vec::new();
    encode_ssh_string(&mut pub_blob, b"ssh-ed25519");
    encode_ssh_string(&mut pub_blob, verifying_key.as_bytes());

    let pub_b64 = BASE64_STD.encode(&pub_blob);
    let public_key_openssh = if comment.trim().is_empty() {
        format!("ssh-ed25519 {pub_b64}")
    } else {
        format!("ssh-ed25519 {pub_b64} {}", comment.trim())
    };

    let digest = Sha256::digest(&pub_blob);
    let fp_b64 = BASE64_STD.encode(digest).trim_end_matches('=').to_string();
    let sha256_fingerprint = format!("SHA256:{fp_b64}");

    // OpenSSH v1 unencrypted private key binary blob (stored inside encrypted .nxts vault)
    let mut openssh_priv = Vec::new();
    openssh_priv.extend_from_slice(b"openssh-key-v1\0");
    encode_ssh_string(&mut openssh_priv, b"none");
    encode_ssh_string(&mut openssh_priv, b"none");
    encode_ssh_string(&mut openssh_priv, &[]);
    openssh_priv.extend_from_slice(&1u32.to_be_bytes());
    encode_ssh_string(&mut openssh_priv, &pub_blob);

    let mut priv_section = Vec::new();
    let check_int: u32 = OsRng.next_u32();
    priv_section.extend_from_slice(&check_int.to_be_bytes());
    priv_section.extend_from_slice(&check_int.to_be_bytes());
    encode_ssh_string(&mut priv_section, b"ssh-ed25519");
    encode_ssh_string(&mut priv_section, verifying_key.as_bytes());

    let mut expanded_secret = [0u8; 64];
    expanded_secret[0..32].copy_from_slice(&signing_key.to_bytes());
    expanded_secret[32..64].copy_from_slice(verifying_key.as_bytes());
    encode_ssh_string(&mut priv_section, &expanded_secret);
    expanded_secret.zeroize();

    encode_ssh_string(&mut priv_section, comment.as_bytes());
    let mut pad_byte = 1u8;
    while priv_section.len() % 8 != 0 {
        priv_section.push(pad_byte);
        pad_byte = pad_byte.wrapping_add(1);
    }
    encode_ssh_string(&mut openssh_priv, &priv_section);
    priv_section.zeroize();

    let wrapped_b64 = BASE64_STD
        .encode(&openssh_priv)
        .as_bytes()
        .chunks(70)
        .map(|line| std::str::from_utf8(line).unwrap_or(""))
        .collect::<Vec<_>>()
        .join("\n");
    openssh_priv.zeroize();

    let pem = format!(
        "-----BEGIN OPENSSH PRIVATE KEY-----\n{wrapped_b64}\n-----END OPENSSH PRIVATE KEY-----\n"
    );

    // Register in the in-memory SSH Agent
    let key_id = sha256_fingerprint.clone();
    register_agent_signing_key(&key_id, signing_key.to_bytes());

    GeneratedSshKeypair {
        key_id,
        public_key_openssh,
        private_key_openssh_pem: Zeroizing::new(pem),
        sha256_fingerprint,
    }
}

fn agent_store() -> &'static Mutex<HashMap<String, Zeroizing<[u8; 32]>>> {
    static AGENT_KEYS: OnceLock<Mutex<HashMap<String, Zeroizing<[u8; 32]>>>> = OnceLock::new();
    AGENT_KEYS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn register_agent_signing_key(key_id: &str, secret_seed: [u8; 32]) {
    if let Ok(mut guard) = agent_store().lock() {
        guard.insert(key_id.to_string(), Zeroizing::new(secret_seed));
    }
}

pub fn clear_ssh_agent() {
    if let Ok(mut guard) = agent_store().lock() {
        guard.clear();
    }
}

pub fn sign_ssh_agent_challenge(key_id: &str, challenge: &[u8]) -> Result<Vec<u8>, CryptoError> {
    let guard = agent_store()
        .lock()
        .map_err(|_| CryptoError::SshKeyError("SSH agent mutex poisoned".into()))?;
    let seed = guard
        .get(key_id)
        .ok_or_else(|| CryptoError::SshKeyError(format!("Key not loaded in agent: {key_id}")))?;
    let signing_key = SigningKey::from_bytes(seed);
    let signature = signing_key.sign(challenge);
    Ok(signature.to_bytes().to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_nxts_vault_seal_and_open() {
        let profile = Argon2idProfile {
            memory_kib: 8_192,
            iterations: 1,
            parallelism: 1,
        };
        let secret = b"correct-horse-battery-staple-enterprise";
        let payload = br#"{"documentName":"Prod Core Vault","connections":[]}"#;

        let sealed = seal_nxts_vault(payload, secret, 42, profile).expect("seal must succeed");
        assert_eq!(&sealed[0..6], &NXTS_MAGIC_V2);

        let opened = open_nxts_vault(&sealed, secret).expect("open must succeed");
        assert_eq!(opened.revision, 42);
        assert_eq!(opened.plaintext.as_slice(), payload);

        let wrong = open_nxts_vault(&sealed, b"wrong-passphrase");
        assert!(matches!(wrong, Err(CryptoError::AuthenticationFailed)));
    }

    #[test]
    fn ed25519_keypair_and_agent_sign() {
        let kp = generate_ed25519_ssh_keypair("ops@nexusts.app");
        assert!(kp.public_key_openssh.starts_with("ssh-ed25519 "));
        assert!(kp.sha256_fingerprint.starts_with("SHA256:"));
        let sig = sign_ssh_agent_challenge(&kp.key_id, b"session-exchange-hash").unwrap();
        assert_eq!(sig.len(), 64);
    }
}
