import { requireNativeModule } from "expo-modules-core";

export type CompressPdfNativeResult = {
  /** file:// URI of the compressed PDF (or the original if not compressed). */
  uri: string;
  /** Size in bytes of the returned file. */
  size: number;
  /** Size in bytes of the input file. */
  originalSize: number;
  /** True when a smaller file was produced and returned. */
  compressed: boolean;
};

type PdfCompressorNativeModule = {
  compressPdf(
    uri: string,
    dpi: number,
    quality: number,
  ): Promise<CompressPdfNativeResult>;
};

let nativeModule: PdfCompressorNativeModule | null = null;
try {
  nativeModule = requireNativeModule(
    "PdfCompressor",
  ) as unknown as PdfCompressorNativeModule;
} catch {
  // Native module not linked (e.g. Expo Go, or the app hasn't been rebuilt yet).
  // Callers can check isPdfCompressorAvailable() and fall back gracefully.
  nativeModule = null;
}

export default nativeModule;
