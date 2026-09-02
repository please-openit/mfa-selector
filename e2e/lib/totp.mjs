import { createHmac } from 'node:crypto';

/**
 * TOTP as Keycloak computes it: the shared secret is used as raw bytes, see
 * org.keycloak.models.utils.HmacOTP#generateHOTP.
 */
export function totp(secret, { period = 30, digits = 6, algorithm = 'sha1', now = Date.now() } = {}) {
  const counter = Buffer.alloc(8);
  counter.writeBigInt64BE(BigInt(Math.floor(now / 1000 / period)));

  const digest = createHmac(algorithm, Buffer.from(secret, 'utf8')).update(counter).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const binary = ((digest[offset] & 0x7f) << 24)
    | ((digest[offset + 1] & 0xff) << 16)
    | ((digest[offset + 2] & 0xff) << 8)
    | (digest[offset + 3] & 0xff);

  return String(binary % 10 ** digits).padStart(digits, '0');
}
