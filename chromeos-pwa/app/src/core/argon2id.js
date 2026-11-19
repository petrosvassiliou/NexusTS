// Pure RFC 9106 Argon2id (Version 0x13) + RFC 7693 Blake2b-512 Engine
// Bit-for-bit identical to Rust `argon2` crate (`nexusts-crypto`) & BouncyCastle (`NxtsVaultSerializer.kt`).
// Uses typed Int32Array split-64 arithmetic for zero BigInt allocation overhead in hot compression loops.

const BLAKE2B_IV_LO = new Uint32Array([
  0xf3bcc908, 0x84caa73b, 0xfe94f82b, 0x5f1d36f1,
  0xade682d1, 0x2b3e6c1f, 0xfb41bd6b, 0x137e2179
]);
const BLAKE2B_IV_HI = new Uint32Array([
  0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
  0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
]);

const BLAKE2B_SIGMA = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
  [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
  [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
  [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
  [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
  [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
  [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
  [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
  [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
  [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0],
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
  [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3]
];

export function blake2b(inputBytes, outLen = 64) {
  const hLo = new Uint32Array(BLAKE2B_IV_LO);
  const hHi = new Uint32Array(BLAKE2B_IV_HI);
  hLo[0] ^= (0x01010000 ^ outLen) >>> 0;

  const vLo = new Uint32Array(16);
  const vHi = new Uint32Array(16);
  const mLo = new Uint32Array(16);
  const mHi = new Uint32Array(16);

  const b2G = (a, b, c, d, xLo, xHi, yLo, yHi) => {
    let sLo = (vLo[a] + vLo[b]) >>> 0;
    let sHi = (vHi[a] + vHi[b] + (sLo < vLo[a] ? 1 : 0)) >>> 0;
    let tLo = (sLo + xLo) >>> 0;
    let tHi = (sHi + xHi + (tLo < sLo ? 1 : 0)) >>> 0;
    vLo[a] = tLo;
    vHi[a] = tHi;

    let dxLo = (vLo[d] ^ tLo) >>> 0;
    let dxHi = (vHi[d] ^ tHi) >>> 0;
    vLo[d] = dxHi;
    vHi[d] = dxLo;

    sLo = (vLo[c] + dxHi) >>> 0;
    sHi = (vHi[c] + dxLo + (sLo < vLo[c] ? 1 : 0)) >>> 0;
    vLo[c] = sLo;
    vHi[c] = sHi;

    dxLo = (vLo[b] ^ sLo) >>> 0;
    dxHi = (vHi[b] ^ sHi) >>> 0;
    vLo[b] = ((dxLo >>> 24) | (dxHi << 8)) >>> 0;
    vHi[b] = ((dxHi >>> 24) | (dxLo << 8)) >>> 0;

    sLo = (vLo[a] + vLo[b]) >>> 0;
    sHi = (vHi[a] + vHi[b] + (sLo < vLo[a] ? 1 : 0)) >>> 0;
    tLo = (sLo + yLo) >>> 0;
    tHi = (sHi + yHi + (tLo < sLo ? 1 : 0)) >>> 0;
    vLo[a] = tLo;
    vHi[a] = tHi;

    dxLo = (vLo[d] ^ tLo) >>> 0;
    dxHi = (vHi[d] ^ tHi) >>> 0;
    vLo[d] = ((dxLo >>> 16) | (dxHi << 16)) >>> 0;
    vHi[d] = ((dxHi >>> 16) | (dxLo << 16)) >>> 0;

    sLo = (vLo[c] + vLo[d]) >>> 0;
    sHi = (vHi[c] + vHi[d] + (sLo < vLo[c] ? 1 : 0)) >>> 0;
    vLo[c] = sLo;
    vHi[c] = sHi;

    dxLo = (vLo[b] ^ sLo) >>> 0;
    dxHi = (vHi[b] ^ sHi) >>> 0;
    vLo[b] = ((dxHi >>> 31) | (dxLo << 1)) >>> 0;
    vHi[b] = ((dxLo >>> 31) | (dxHi << 1)) >>> 0;
  };

  const compress = (block, offset, countLo, countHi, isLast) => {
    for (let i = 0; i < 16; i++) {
      const p = offset + i * 8;
      mLo[i] = (block[p] | (block[p + 1] << 8) | (block[p + 2] << 16) | (block[p + 3] << 24)) >>> 0;
      mHi[i] = (block[p + 4] | (block[p + 5] << 8) | (block[p + 6] << 16) | (block[p + 7] << 24)) >>> 0;
    }
    for (let i = 0; i < 8; i++) {
      vLo[i] = hLo[i];
      vHi[i] = hHi[i];
      vLo[i + 8] = BLAKE2B_IV_LO[i];
      vHi[i + 8] = BLAKE2B_IV_HI[i];
    }
    vLo[12] = (vLo[12] ^ countLo) >>> 0;
    vHi[12] = (vHi[12] ^ countHi) >>> 0;
    if (isLast) {
      vLo[14] = (~vLo[14]) >>> 0;
      vHi[14] = (~vHi[14]) >>> 0;
    }
    for (let r = 0; r < 12; r++) {
      const s = BLAKE2B_SIGMA[r];
      b2G(0, 4, 8, 12, mLo[s[0]], mHi[s[0]], mLo[s[1]], mHi[s[1]]);
      b2G(1, 5, 9, 13, mLo[s[2]], mHi[s[2]], mLo[s[3]], mHi[s[3]]);
      b2G(2, 6, 10, 14, mLo[s[4]], mHi[s[4]], mLo[s[5]], mHi[s[5]]);
      b2G(3, 7, 11, 15, mLo[s[6]], mHi[s[6]], mLo[s[7]], mHi[s[7]]);
      b2G(0, 5, 10, 15, mLo[s[8]], mHi[s[8]], mLo[s[9]], mHi[s[9]]);
      b2G(1, 6, 11, 12, mLo[s[10]], mHi[s[10]], mLo[s[11]], mHi[s[11]]);
      b2G(2, 7, 8, 13, mLo[s[12]], mHi[s[12]], mLo[s[13]], mHi[s[13]]);
      b2G(3, 4, 9, 14, mLo[s[14]], mHi[s[14]], mLo[s[15]], mHi[s[15]]);
    }
    for (let i = 0; i < 8; i++) {
      hLo[i] = (hLo[i] ^ vLo[i] ^ vLo[i + 8]) >>> 0;
      hHi[i] = (hHi[i] ^ vHi[i] ^ vHi[i + 8]) >>> 0;
    }
  };

  const totalLen = inputBytes.length;
  let pos = 0;
  let countLo = 0;
  let countHi = 0;
  while (totalLen - pos > 128) {
    const nextLo = (countLo + 128) >>> 0;
    if (nextLo < countLo) countHi = (countHi + 1) >>> 0;
    countLo = nextLo;
    compress(inputBytes, pos, countLo, countHi, false);
    pos += 128;
  }

  const tail = new Uint8Array(128);
  const rem = totalLen - pos;
  if (rem > 0) {
    tail.set(inputBytes.subarray(pos));
  }
  const nextLo = (countLo + rem) >>> 0;
  if (nextLo < countLo) countHi = (countHi + 1) >>> 0;
  countLo = nextLo;
  compress(tail, 0, countLo, countHi, true);

  const out = new Uint8Array(outLen);
  for (let i = 0; i < outLen; i++) {
    const wordIdx = i >> 3;
    const byteShift = (i & 3) * 8;
    const word = (i & 4) === 0 ? hLo[wordIdx] : hHi[wordIdx];
    out[i] = (word >>> byteShift) & 0xff;
  }
  return out;
}

function writeLe32(dst, offset, val) {
  dst[offset] = val & 0xff;
  dst[offset + 1] = (val >>> 8) & 0xff;
  dst[offset + 2] = (val >>> 16) & 0xff;
  dst[offset + 3] = (val >>> 24) & 0xff;
}

function blake2bLong(inputBytes, outLen) {
  const prefixed = new Uint8Array(4 + inputBytes.length);
  writeLe32(prefixed, 0, outLen);
  prefixed.set(inputBytes, 4);

  if (outLen <= 64) {
    return blake2b(prefixed, outLen);
  }
  const out = new Uint8Array(outLen);
  let v = blake2b(prefixed, 64);
  out.set(v.subarray(0, 32), 0);
  let outPos = 32;
  let remaining = outLen - 32;
  while (remaining > 64) {
    v = blake2b(v, 64);
    out.set(v.subarray(0, 32), outPos);
    outPos += 32;
    remaining -= 32;
  }
  v = blake2b(v, remaining);
  out.set(v, outPos);
  return out;
}

// 64-bit unsigned multiply of 32-bit unsigned integers `a * b * 2` added to 64-bit `(xHi:xLo + yHi:yLo)`
// `2 * (a * b)`: note `a * b` can reach `(2^32 - 1)^2 = 2^64 - 2^33 + 1`.
function fBlaMkaAdd(w, ia, ib) {
  const xLo = w[ia];
  const xHi = w[ia + 1];
  const yLo = w[ib];
  const yHi = w[ib + 1];

  // m = xLo * yLo (exact 64-bit product via 16-bit split)
  const a0 = xLo & 0xffff;
  const a1 = xLo >>> 16;
  const b0 = yLo & 0xffff;
  const b1 = yLo >>> 16;

  const p0 = Math.imul(a0, b0) >>> 0;
  const p1 = Math.imul(a0, b1) >>> 0;
  const p2 = Math.imul(a1, b0) >>> 0;
  const p3 = Math.imul(a1, b1) >>> 0;

  const mid = ((p0 >>> 16) + (p1 & 0xffff) + (p2 & 0xffff)) >>> 0;
  const mulLo = ((mid << 16) | (p0 & 0xffff)) >>> 0;
  const mulHi = (p3 + (p1 >>> 16) + (p2 >>> 16) + (mid >>> 16)) >>> 0;

  // 2 * m
  const twoLo = (mulLo << 1) >>> 0;
  const twoHi = ((mulHi << 1) | (mulLo >>> 31)) >>> 0;

  // x + y
  const sum1Lo = (xLo + yLo) >>> 0;
  const sum1Hi = (xHi + yHi + (sum1Lo < xLo ? 1 : 0)) >>> 0;

  // (x + y) + 2*m
  const resLo = (sum1Lo + twoLo) >>> 0;
  const resHi = (sum1Hi + twoHi + (resLo < sum1Lo ? 1 : 0)) >>> 0;

  w[ia] = resLo;
  w[ia + 1] = resHi;
}

function gbRound(w, a, b, c, d) {
  const ia = a * 2;
  const ib = b * 2;
  const ic = c * 2;
  const id = d * 2;

  fBlaMkaAdd(w, ia, ib);
  // d = rotr64(d ^ a, 32)
  let tLo = (w[id] ^ w[ia]) >>> 0;
  let tHi = (w[id + 1] ^ w[ia + 1]) >>> 0;
  w[id] = tHi;
  w[id + 1] = tLo;

  fBlaMkaAdd(w, ic, id);
  // b = rotr64(b ^ c, 24)
  tLo = (w[ib] ^ w[ic]) >>> 0;
  tHi = (w[ib + 1] ^ w[ic + 1]) >>> 0;
  w[ib] = ((tLo >>> 24) | (tHi << 8)) >>> 0;
  w[ib + 1] = ((tHi >>> 24) | (tLo << 8)) >>> 0;

  fBlaMkaAdd(w, ia, ib);
  // d = rotr64(d ^ a, 16)
  tLo = (w[id] ^ w[ia]) >>> 0;
  tHi = (w[id + 1] ^ w[ia + 1]) >>> 0;
  w[id] = ((tLo >>> 16) | (tHi << 16)) >>> 0;
  w[id + 1] = ((tHi >>> 16) | (tLo << 16)) >>> 0;

  fBlaMkaAdd(w, ic, id);
  // b = rotr64(b ^ c, 63) == rotl64(b ^ c, 1)
  tLo = (w[ib] ^ w[ic]) >>> 0;
  tHi = (w[ib + 1] ^ w[ic + 1]) >>> 0;
  w[ib] = ((tHi >>> 31) | (tLo << 1)) >>> 0;
  w[ib + 1] = ((tLo >>> 31) | (tHi << 1)) >>> 0;
}

// Each Argon2 block = 1024 bytes = 256 Uint32 words (128 u64s stored as [lo0, hi0, lo1, hi1, ...])
const BLOCK_WORDS = 256;

function fillBlock(memory, prevOff, refOff, nextOff, withXor, scratchR, scratchZ) {
  for (let i = 0; i < BLOCK_WORDS; i++) {
    const r = (memory[prevOff + i] ^ memory[refOff + i]) >>> 0;
    scratchR[i] = r;
    scratchZ[i] = r;
  }

  // Row-wise P over 8 rows of 16 u64s
  for (let row = 0; row < 8; row++) {
    const base = row * 16;
    gbRound(scratchZ, base + 0, base + 4, base + 8, base + 12);
    gbRound(scratchZ, base + 1, base + 5, base + 9, base + 13);
    gbRound(scratchZ, base + 2, base + 6, base + 10, base + 14);
    gbRound(scratchZ, base + 3, base + 7, base + 11, base + 15);
    gbRound(scratchZ, base + 0, base + 5, base + 10, base + 15);
    gbRound(scratchZ, base + 1, base + 6, base + 11, base + 12);
    gbRound(scratchZ, base + 2, base + 7, base + 8, base + 13);
    gbRound(scratchZ, base + 3, base + 4, base + 9, base + 14);
  }

  // Column-wise P over 8 columns of (2 u64s × 8 rows)
  for (let col = 0; col < 8; col++) {
    const c = col * 2;
    gbRound(scratchZ, c + 0, c + 32, c + 64, c + 96);
    gbRound(scratchZ, c + 1, c + 33, c + 65, c + 97);
    gbRound(scratchZ, c + 16, c + 48, c + 80, c + 112);
    gbRound(scratchZ, c + 17, c + 49, c + 81, c + 113);
    gbRound(scratchZ, c + 0, c + 33, c + 80, c + 113);
    gbRound(scratchZ, c + 1, c + 48, c + 81, c + 96);
    gbRound(scratchZ, c + 16, c + 49, c + 64, c + 97);
    gbRound(scratchZ, c + 17, c + 32, c + 65, c + 112);
  }

  if (withXor) {
    for (let i = 0; i < BLOCK_WORDS; i++) {
      memory[nextOff + i] = (memory[nextOff + i] ^ scratchR[i] ^ scratchZ[i]) >>> 0;
    }
  } else {
    for (let i = 0; i < BLOCK_WORDS; i++) {
      memory[nextOff + i] = (scratchR[i] ^ scratchZ[i]) >>> 0;
    }
  }
}

function mulHi32(a, b) {
  const a0 = a & 0xffff;
  const a1 = a >>> 16;
  const b0 = b & 0xffff;
  const b1 = b >>> 16;
  const p0 = Math.imul(a0, b0) >>> 0;
  const p1 = Math.imul(a0, b1) >>> 0;
  const p2 = Math.imul(a1, b0) >>> 0;
  const p3 = Math.imul(a1, b1) >>> 0;
  const mid = ((p0 >>> 16) + (p1 & 0xffff) + (p2 & 0xffff)) >>> 0;
  return (p3 + (p1 >>> 16) + (p2 >>> 16) + (mid >>> 16)) >>> 0;
}

/**
 * Computes RFC 9106 Argon2id v0x13 tag.
 */
export function argon2idRaw({
  password,
  salt,
  memoryKiB = 65536,
  iterations = 3,
  parallelism = 4,
  tagLength = 32,
  secret = new Uint8Array(0),
  associatedData = new Uint8Array(0)
}) {
  const p = parallelism >>> 0;
  const t = iterations >>> 0;
  const mKiB = memoryKiB >>> 0;
  if (p < 1 || t < 1 || mKiB < 8 * p) {
    throw new Error('Invalid Argon2id security parameters');
  }

  const segmentLength = Math.floor(mKiB / (4 * p));
  const laneLength = segmentLength * 4;
  const memoryBlocks = laneLength * p;

  // Build H_0 input
  const h0InputLen = 40 + password.length + salt.length + secret.length + associatedData.length;
  const h0Input = new Uint8Array(h0InputLen);
  let off = 0;
  writeLe32(h0Input, off, p); off += 4;
  writeLe32(h0Input, off, tagLength); off += 4;
  writeLe32(h0Input, off, mKiB); off += 4;
  writeLe32(h0Input, off, t); off += 4;
  writeLe32(h0Input, off, 0x13); off += 4; // Version 0x13
  writeLe32(h0Input, off, 2); off += 4;    // Type 2 = Argon2id
  writeLe32(h0Input, off, password.length); off += 4;
  h0Input.set(password, off); off += password.length;
  writeLe32(h0Input, off, salt.length); off += 4;
  h0Input.set(salt, off); off += salt.length;
  writeLe32(h0Input, off, secret.length); off += 4;
  h0Input.set(secret, off); off += secret.length;
  writeLe32(h0Input, off, associatedData.length); off += 4;
  h0Input.set(associatedData, off);

  const h0 = blake2b(h0Input, 64);
  h0Input.fill(0);

  const memory = new Uint32Array(memoryBlocks * BLOCK_WORDS);
  const blockSeed = new Uint8Array(72);
  blockSeed.set(h0, 0);

  const bytesToWords = (bytes, dstWords, wordOffset) => {
    for (let i = 0; i < BLOCK_WORDS; i++) {
      const b = i * 4;
      dstWords[wordOffset + i] =
        (bytes[b] | (bytes[b + 1] << 8) | (bytes[b + 2] << 16) | (bytes[b + 3] << 24)) >>> 0;
    }
  };

  for (let lane = 0; lane < p; lane++) {
    writeLe32(blockSeed, 68, lane);
    writeLe32(blockSeed, 64, 0);
    bytesToWords(blake2bLong(blockSeed, 1024), memory, (lane * laneLength + 0) * BLOCK_WORDS);
    writeLe32(blockSeed, 64, 1);
    bytesToWords(blake2bLong(blockSeed, 1024), memory, (lane * laneLength + 1) * BLOCK_WORDS);
  }

  const scratchR = new Uint32Array(BLOCK_WORDS);
  const scratchZ = new Uint32Array(BLOCK_WORDS);
  // Aux 3-block buffer for Argon2i data-independent address generation: [zero, input, addresses]
  const addrBuf = new Uint32Array(3 * BLOCK_WORDS);
  const ZERO_OFF = 0;
  const INPUT_OFF = BLOCK_WORDS;
  const ADDR_OFF = 2 * BLOCK_WORDS;

  const nextAddresses = () => {
    // Increment 64-bit counter at input[6]
    const nextLo = (addrBuf[INPUT_OFF + 12] + 1) >>> 0;
    addrBuf[INPUT_OFF + 12] = nextLo;
    if (nextLo === 0) {
      addrBuf[INPUT_OFF + 13] = (addrBuf[INPUT_OFF + 13] + 1) >>> 0;
    }
    fillBlock(addrBuf, ZERO_OFF, INPUT_OFF, ADDR_OFF, false, scratchR, scratchZ);
    fillBlock(addrBuf, ZERO_OFF, ADDR_OFF, ADDR_OFF, false, scratchR, scratchZ);
  };

  for (let pass = 0; pass < t; pass++) {
    const withXor = pass > 0;
    for (let slice = 0; slice < 4; slice++) {
      const dataIndependent = pass === 0 && slice < 2;
      for (let lane = 0; lane < p; lane++) {
        if (dataIndependent) {
          addrBuf.fill(0, INPUT_OFF, INPUT_OFF + BLOCK_WORDS);
          addrBuf[INPUT_OFF + 0] = pass;
          addrBuf[INPUT_OFF + 2] = lane;
          addrBuf[INPUT_OFF + 4] = slice;
          addrBuf[INPUT_OFF + 6] = memoryBlocks;
          addrBuf[INPUT_OFF + 8] = t;
          addrBuf[INPUT_OFF + 10] = 2; // Argon2id
        }

        const startIdx = pass === 0 && slice === 0 ? 2 : 0;
        for (let idx = startIdx; idx < segmentLength; idx++) {
          if (dataIndependent && idx % 128 === 0) {
            if (idx === startIdx && startIdx === 2) {
              nextAddresses();
            } else if (idx % 128 === 0) {
              nextAddresses();
            }
          } else if (dataIndependent && idx === startIdx) {
            nextAddresses();
          }

          const currCol = slice * segmentLength + idx;
          const prevCol = currCol === 0 ? laneLength - 1 : currCol - 1;
          const prevOff = (lane * laneLength + prevCol) * BLOCK_WORDS;
          const currOff = (lane * laneLength + currCol) * BLOCK_WORDS;

          let j1;
          let j2;
          if (dataIndependent) {
            const addrWord = ADDR_OFF + (idx % 128) * 2;
            j1 = addrBuf[addrWord];
            j2 = addrBuf[addrWord + 1];
          } else {
            j1 = memory[prevOff];
            j2 = memory[prevOff + 1];
          }

          const refLane = pass === 0 && slice === 0 ? lane : (j2 % p);
          let refAreaSize;
          if (pass === 0) {
            if (slice === 0) {
              refAreaSize = idx - 1;
            } else if (refLane === lane) {
              refAreaSize = slice * segmentLength + idx - 1;
            } else {
              refAreaSize = slice * segmentLength - (idx === 0 ? 1 : 0);
            }
          } else if (refLane === lane) {
            refAreaSize = laneLength - segmentLength + idx - 1;
          } else {
            refAreaSize = laneLength - segmentLength - (idx === 0 ? 1 : 0);
          }

          const x = mulHi32(j1, j1);
          const y = mulHi32(refAreaSize >>> 0, x);
          const relPos = refAreaSize - 1 - y;
          const startPos = pass === 0 || slice === 3 ? 0 : (slice + 1) * segmentLength;
          const refCol = (startPos + relPos) % laneLength;
          const refOff = (refLane * laneLength + refCol) * BLOCK_WORDS;

          fillBlock(memory, prevOff, refOff, currOff, withXor, scratchR, scratchZ);
        }
      }
    }
  }

  const finalWords = new Uint32Array(BLOCK_WORDS);
  for (let lane = 0; lane < p; lane++) {
    const lastOff = (lane * laneLength + (laneLength - 1)) * BLOCK_WORDS;
    for (let i = 0; i < BLOCK_WORDS; i++) {
      finalWords[i] = (finalWords[i] ^ memory[lastOff + i]) >>> 0;
    }
  }

  const finalBytes = new Uint8Array(1024);
  for (let i = 0; i < BLOCK_WORDS; i++) {
    writeLe32(finalBytes, i * 4, finalWords[i]);
  }
  const tag = blake2bLong(finalBytes, tagLength);
  memory.fill(0);
  finalWords.fill(0);
  finalBytes.fill(0);
  return tag;
}
