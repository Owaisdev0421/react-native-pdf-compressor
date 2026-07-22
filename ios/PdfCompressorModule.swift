import ExpoModulesCore
import PDFKit
import UIKit

public class PdfCompressorModule: Module {
  public func definition() -> ModuleDefinition {
    Name("PdfCompressor")

    // Runs on Expo's async module queue (off the main thread), so the heavy
    // rasterization doesn't block the UI.
    AsyncFunction("compressPdf") { (uri: String, dpi: Double, quality: Double) -> [String: Any] in
      return try PdfCompressor.compress(uri: uri, dpi: dpi, quality: quality)
    }
  }
}

private enum PdfCompressError: Error, LocalizedError {
  case cannotOpen
  case passwordProtected
  case emptyDocument

  var errorDescription: String? {
    switch self {
    case .cannotOpen: return "Could not open the PDF."
    case .passwordProtected: return "Password-protected PDFs are not supported."
    case .emptyDocument: return "The PDF has no pages."
    }
  }
}

private enum PdfCompressor {
  /// Caps the longest rasterized edge so huge pages (e.g. A0 posters, large
  /// scans) can't allocate bitmaps that OOM-kill the app on older devices.
  private static let maxPixelDimension: CGFloat = 4096

  static func compress(uri: String, dpi: Double, quality: Double) throws -> [String: Any] {
    let inputURL: URL
    if let parsed = URL(string: uri), parsed.isFileURL {
      inputURL = parsed
    } else {
      inputURL = URL(fileURLWithPath: uri.replacingOccurrences(of: "file://", with: ""))
    }

    guard let document = PDFDocument(url: inputURL) else {
      throw PdfCompressError.cannotOpen
    }
    if document.isLocked || document.isEncrypted {
      throw PdfCompressError.passwordProtected
    }
    guard document.pageCount > 0 else {
      throw PdfCompressError.emptyDocument
    }

    let originalSize = fileSize(inputURL)
    let scale = CGFloat(dpi) / 72.0
    let jpegQuality = CGFloat(max(0.1, min(1.0, quality)))

    let outputURL = FileManager.default
      .urls(for: .cachesDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("compressed_\(UUID().uuidString).pdf")

    // Initial bounds are overridden per page via beginPage(withBounds:).
    let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))

    try renderer.writePDF(to: outputURL) { context in
      for index in 0..<document.pageCount {
        guard let page = document.page(at: index) else { continue }
        let pageRect = page.bounds(for: .mediaBox)
        var pixelSize = CGSize(
          width: max(1, pageRect.width * scale),
          height: max(1, pageRect.height * scale)
        )

        // Clamp oversized pages to keep memory bounded.
        let longestEdge = max(pixelSize.width, pixelSize.height)
        if longestEdge > maxPixelDimension {
          let shrink = maxPixelDimension / longestEdge
          pixelSize = CGSize(width: pixelSize.width * shrink, height: pixelSize.height * shrink)
        }

        // 1. Render the page to a bitmap at the target DPI.
        let pageImage = renderPage(page, pageRect: pageRect, pixelSize: pixelSize)

        // 2. JPEG-encode it, then wrap as a JPEG-backed CGImage so Core Graphics
        //    embeds it with DCTDecode — this is what keeps the output small.
        guard
          let jpegData = pageImage.jpegData(compressionQuality: jpegQuality),
          let provider = CGDataProvider(data: jpegData as CFData),
          let cgImage = CGImage(
            jpegDataProviderSource: provider,
            decode: nil,
            shouldInterpolate: true,
            intent: .defaultIntent
          )
        else { continue }

        context.beginPage(withBounds: pageRect, pageInfo: [:])
        let cg = context.cgContext
        cg.saveGState()
        // Flip so the CGImage draws right-side up in the UIKit PDF context.
        cg.translateBy(x: pageRect.minX, y: pageRect.maxY)
        cg.scaleBy(x: 1, y: -1)
        cg.draw(cgImage, in: CGRect(x: 0, y: 0, width: pageRect.width, height: pageRect.height))
        cg.restoreGState()
      }
    }

    let newSize = fileSize(outputURL)
    if newSize > 0, newSize < originalSize {
      return [
        "uri": outputURL.absoluteString,
        "size": newSize,
        "originalSize": originalSize,
        "compressed": true,
      ]
    }

    // Compression didn't help (e.g. already-optimized PDF) — keep the original.
    try? FileManager.default.removeItem(at: outputURL)
    return [
      "uri": uri,
      "size": originalSize,
      "originalSize": originalSize,
      "compressed": false,
    ]
  }

  private static func renderPage(_ page: PDFPage, pageRect: CGRect, pixelSize: CGSize) -> UIImage {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let imageRenderer = UIGraphicsImageRenderer(size: pixelSize, format: format)
    return imageRenderer.image { ctx in
      UIColor.white.set()
      ctx.fill(CGRect(origin: .zero, size: pixelSize))
      let cg = ctx.cgContext
      cg.translateBy(x: 0, y: pixelSize.height)
      cg.scaleBy(x: pixelSize.width / pageRect.width, y: -pixelSize.height / pageRect.height)
      cg.translateBy(x: -pageRect.minX, y: -pageRect.minY)
      page.draw(with: .mediaBox, to: cg)
    }
  }

  private static func fileSize(_ url: URL) -> Int {
    let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
    return (attributes?[.size] as? Int) ?? 0
  }
}
