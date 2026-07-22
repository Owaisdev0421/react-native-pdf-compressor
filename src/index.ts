import PdfCompressorModule, {
  CompressPdfNativeResult,
} from "./PdfCompressorModule";

export type CompressPdfResult = CompressPdfNativeResult;

export type CompressPdfOptions = {
  /**
   * Rasterization resolution in dots-per-inch. Higher = sharper output but a
   * bigger file. 150 keeps scanned documents and tables readable.
   * @default 150
   */
  dpi?: number;
  /**
   * JPEG quality between 0.1 and 1.0.
   * @default 0.6
   */
  quality?: number;
};

/**
 * Whether the native PDF compressor is linked in the current build.
 *
 * Returns `false` in Expo Go or before the app has been rebuilt with this
 * module — use it to fall back to the original file instead of crashing.
 */
export const isPdfCompressorAvailable = (): boolean =>
  PdfCompressorModule != null;

/**
 * Compresses a PDF on-device by rasterizing each page at `dpi` and re-embedding
 * it as JPEG (`quality`). No network, no server — everything runs locally.
 *
 * If the compressed output would be larger than the input (e.g. the PDF is
 * already optimized), the original URI is returned with `compressed: false`.
 *
 * If the native module is not linked (Expo Go / app not rebuilt), resolves as a
 * no-op with the original URI so callers never crash.
 *
 * @param uri `file://` URI (or absolute path). On Android, `content://` URIs
 *   are also accepted.
 * @throws If the file cannot be opened or is password-protected.
 */
export const compressPdf = async (
  uri: string,
  options?: CompressPdfOptions,
): Promise<CompressPdfResult> => {
  if (!PdfCompressorModule) {
    return { uri, size: 0, originalSize: 0, compressed: false };
  }
  const dpi = options?.dpi ?? 150;
  const quality = options?.quality ?? 0.6;
  return PdfCompressorModule.compressPdf(uri, dpi, quality);
};
