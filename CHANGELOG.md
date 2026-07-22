# Changelog

## 0.1.0

Initial release.

- `compressPdf(uri, { dpi, quality })` — on-device PDF compression for iOS & Android
- `isPdfCompressorAvailable()` — safe runtime check for the native module
- Memory-safe rasterization (oversized pages are clamped)
- `content://` URI support on Android
- Clear errors for password-protected and unreadable PDFs
