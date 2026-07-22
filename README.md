# react-native-pdf-compressor

On-device PDF compression for **React Native** and **Expo**. Shrinks large PDFs (scanned documents, receipts, camera-generated PDFs) before upload — no server, no network, everything runs locally on the device.

- 📱 **iOS + Android** — native implementations on both platforms (Swift / Kotlin)
- 🔌 **Zero native dependencies** — uses only OS APIs (PDFKit on iOS, PdfRenderer on Android). Adds virtually nothing to your app size.
- 🛡️ **Fail-safe by design** — if compression doesn't reduce the file size, you get the original file back. Never blocks your upload flow.
- 🧠 **Memory-safe** — oversized pages are clamped so low-RAM devices don't crash.
- ⚡ **Off the UI thread** — compression runs on a background queue.

## How it works

Each page is rasterized at a target DPI and re-embedded as a JPEG (`DCTDecode`), which is what makes scanned/image-heavy PDFs dramatically smaller. A 25 MB scanned document typically compresses to 1–3 MB at the default settings while staying perfectly readable.

> **Note:** because pages are rasterized, text in the output PDF is no longer selectable/searchable. This trade-off is ideal for scans, receipts, and photos-as-PDF — not for PDFs where you need to preserve live text.

## Installation

### Expo (development build / EAS)

```sh
npx expo install react-native-pdf-compressor
```

Then rebuild your app (`npx expo run:ios` / `npx expo run:android` or an EAS build). Does **not** work in Expo Go (it contains native code).

### Bare React Native

```sh
npm install react-native-pdf-compressor
npx pod-install
```

Requires [Expo Modules](https://docs.expo.dev/bare/installing-expo-modules/) to be installed in your project (`npx install-expo-modules`), which is a one-time setup and works in any React Native app.

## Usage

```ts
import { compressPdf, isPdfCompressorAvailable } from "react-native-pdf-compressor";

const result = await compressPdf(fileUri, { dpi: 150, quality: 0.6 });

console.log(result.uri);          // file:// URI of the compressed PDF
console.log(result.size);         // bytes after compression
console.log(result.originalSize); // bytes before compression
console.log(result.compressed);   // false if the original was already smaller
```

Typical upload flow:

```ts
async function prepareForUpload(uri: string): Promise<string> {
  try {
    const { uri: outUri } = await compressPdf(uri);
    return outUri; // compressed file, or the original if compression didn't help
  } catch {
    return uri; // e.g. password-protected PDF — just upload the original
  }
}
```

## API

### `compressPdf(uri, options?)`

| Param | Type | Default | Description |
| --- | --- | --- | --- |
| `uri` | `string` | — | `file://` URI or absolute path. Android also accepts `content://` URIs. |
| `options.dpi` | `number` | `150` | Rasterization resolution. Higher = sharper but bigger. `100` for aggressive compression, `200`+ for print quality. |
| `options.quality` | `number` | `0.6` | JPEG quality, `0.1`–`1.0`. |

Returns `Promise<CompressPdfResult>`:

```ts
type CompressPdfResult = {
  uri: string;          // output file (in the app cache directory) or the original
  size: number;         // bytes of the returned file
  originalSize: number; // bytes of the input file
  compressed: boolean;  // true only if a smaller file was produced
};
```

Throws if the file can't be opened or the PDF is password-protected.

### `isPdfCompressorAvailable()`

Returns `true` when the native module is linked in the current build. Returns `false` in Expo Go or before a rebuild — in that case `compressPdf` resolves as a no-op with the original URI, so your app never crashes.

## Compatibility

| Platform | Minimum | Notes |
| --- | --- | --- |
| Android | API 21 (Android 5.0, 2014) | Uses `android.graphics.pdf.PdfRenderer` — no third-party libs |
| iOS | iOS 13.4 | Uses `PDFKit` — no third-party libs. (Your app's Expo SDK / RN version may set a higher minimum.) |
| Expo | SDK 50+ | Development builds & EAS. Not Expo Go. |
| React Native | 0.73+ | New & old architecture (via Expo Modules API) |

## Choosing settings

| Use case | `dpi` | `quality` |
| --- | --- | --- |
| Receipts / forms for record-keeping | 100–120 | 0.5 |
| Scanned documents (default) | 150 | 0.6 |
| Documents that may be printed | 200 | 0.7 |

## Limitations

- Output pages are images — text is not selectable/searchable (fine for scans, wrong tool for text-first PDFs).
- Password-protected PDFs are rejected with an error.
- If the input is already highly optimized, the original file is returned unchanged (`compressed: false`) — this is by design.

## License

[MIT](./LICENSE)
