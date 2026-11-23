import { describe, expect, it, vi } from "vitest";
import { newIdempotencyKey } from "./client";

describe("Idempotency-Key", () => {
  it("is a valid key (8-128 of [A-Za-z0-9_-]) and unique, without crypto.randomUUID (insecure origins)", () => {
    vi.spyOn(crypto, "randomUUID").mockImplementation(() => {
      throw new TypeError("crypto.randomUUID is not available in insecure contexts");
    });
    const keys = new Set(Array.from({ length: 100 }, () => newIdempotencyKey()));

    expect(keys.size).toBe(100);
    for (const key of keys) {
      expect(key).toMatch(/^[A-Za-z0-9_-]{8,128}$/);
    }
  });
});
