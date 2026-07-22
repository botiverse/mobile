import { createHash } from "node:crypto";

export function captureAndroidScreenshotWithPostProof(capturePng, registerProof) {
  const png = capturePng();
  const imageSha256 = createHash("sha256").update(png).digest("hex");
  const boundary = registerProof(imageSha256);
  return { png, imageSha256, boundary };
}
