package expo.modules.pdfcompressor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class PdfCompressorModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("PdfCompressor")

    // AsyncFunctions run off the main thread on Expo's module queue.
    AsyncFunction("compressPdf") { uri: String, dpi: Double, quality: Double ->
      compress(uri, dpi, quality)
    }
  }

  private companion object {
    /**
     * Caps the longest rasterized edge so huge pages (e.g. A0 posters, large
     * scans) can't allocate bitmaps that OOM-kill the app on low-RAM devices.
     */
    const val MAX_PIXEL_DIMENSION = 4096
  }

  private fun compress(uriString: String, dpi: Double, quality: Double): Map<String, Any> {
    val context = appContext.reactContext
      ?: throw CodedException("ERR_PDF_COMPRESS", "No application context", null)

    val inputFile = resolveToFile(context, uriString)
    if (!inputFile.exists() || inputFile.length() == 0L) {
      throw CodedException("ERR_PDF_COMPRESS", "Could not open the PDF.", null)
    }
    val originalSize = inputFile.length()
    val scale = (dpi / 72.0).toFloat()
    val jpegQuality = (quality.coerceIn(0.1, 1.0) * 100).toInt()

    val pfd = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try {
      PdfRenderer(pfd)
    } catch (e: SecurityException) {
      pfd.close()
      throw CodedException("ERR_PDF_PASSWORD", "Password-protected PDFs are not supported.", e)
    } catch (e: Exception) {
      pfd.close()
      throw CodedException("ERR_PDF_COMPRESS", "Could not open the PDF.", e)
    }

    val pages = ArrayList<PdfPageImage>()
    try {
      if (renderer.pageCount == 0) {
        throw CodedException("ERR_PDF_COMPRESS", "The PDF has no pages.", null)
      }
      for (i in 0 until renderer.pageCount) {
        val page = renderer.openPage(i)
        val ptWidth = page.width   // PDF points (72 dpi)
        val ptHeight = page.height
        var pxWidth = Math.max(1, (ptWidth * scale).toInt())
        var pxHeight = Math.max(1, (ptHeight * scale).toInt())

        // Clamp oversized pages to keep memory bounded.
        val longestEdge = Math.max(pxWidth, pxHeight)
        if (longestEdge > MAX_PIXEL_DIMENSION) {
          val shrink = MAX_PIXEL_DIMENSION.toFloat() / longestEdge
          pxWidth = Math.max(1, (pxWidth * shrink).toInt())
          pxHeight = Math.max(1, (pxHeight * shrink).toInt())
        }

        val bitmap = Bitmap.createBitmap(pxWidth, pxHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        val jpegStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegStream)
        bitmap.recycle()

        pages.add(PdfPageImage(jpegStream.toByteArray(), pxWidth, pxHeight, ptWidth, ptHeight))
      }
    } finally {
      renderer.close()
      pfd.close()
    }

    val outFile = File(context.cacheDir, "compressed_${System.nanoTime()}.pdf")
    FileOutputStream(outFile).use { it.write(buildPdf(pages)) }

    val newSize = outFile.length()
    return if (newSize in 1 until originalSize) {
      mapOf(
        "uri" to Uri.fromFile(outFile).toString(),
        "size" to newSize.toDouble(),
        "originalSize" to originalSize.toDouble(),
        "compressed" to true
      )
    } else {
      // Compression didn't help — keep the original.
      outFile.delete()
      mapOf(
        "uri" to uriString,
        "size" to originalSize.toDouble(),
        "originalSize" to originalSize.toDouble(),
        "compressed" to false
      )
    }
  }

  private fun resolveToFile(context: Context, uriString: String): File {
    val uri = Uri.parse(uriString)
    if (uri.scheme == null || uri.scheme == "file") {
      return File(uri.path ?: uriString)
    }
    // content:// — copy to a temp file PdfRenderer can open.
    val temp = File(context.cacheDir, "pdf_in_${System.nanoTime()}.pdf")
    context.contentResolver.openInputStream(uri).use { input ->
      requireNotNull(input) { "Cannot open input stream for $uriString" }
      FileOutputStream(temp).use { output -> input.copyTo(output) }
    }
    return temp
  }

  /**
   * Builds a minimal PDF where each page is a single JPEG image embedded with the
   * DCTDecode filter (so the JPEG bytes are stored as-is — that's what keeps the
   * file small). Object byte offsets are read straight from the output stream to
   * keep the xref table exact.
   */
  private fun buildPdf(pages: List<PdfPageImage>): ByteArray {
    val out = ByteArrayOutputStream()
    val totalObjects = 2 + pages.size * 3
    val offsets = IntArray(totalObjects + 1)

    fun writeAscii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))

    out.write(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A)) // %PDF-1.4\n
    out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A)) // binary marker

    // 1 = Catalog
    offsets[1] = out.size()
    writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

    // 2 = Pages
    val kids = StringBuilder()
    for (i in pages.indices) kids.append("${3 + i * 3} 0 R ")
    offsets[2] = out.size()
    writeAscii("2 0 obj\n<< /Type /Pages /Kids [ $kids] /Count ${pages.size} >>\nendobj\n")

    for (i in pages.indices) {
      val page = pages[i]
      val pageObj = 3 + i * 3
      val imageObj = pageObj + 1
      val contentObj = pageObj + 2

      // Page
      offsets[pageObj] = out.size()
      writeAscii(
        "$pageObj 0 obj\n" +
          "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${page.ptWidth} ${page.ptHeight}] " +
          "/Resources << /XObject << /Im0 $imageObj 0 R >> >> /Contents $contentObj 0 R >>\n" +
          "endobj\n"
      )

      // Image XObject (JPEG / DCTDecode)
      offsets[imageObj] = out.size()
      writeAscii(
        "$imageObj 0 obj\n" +
          "<< /Type /XObject /Subtype /Image /Width ${page.pxWidth} /Height ${page.pxHeight} " +
          "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\n" +
          "stream\n"
      )
      out.write(page.jpeg)
      writeAscii("\nendstream\nendobj\n")

      // Content stream — scale the image to fill the page.
      val content = "q ${page.ptWidth} 0 0 ${page.ptHeight} 0 0 cm /Im0 Do Q\n"
      val contentBytes = content.toByteArray(Charsets.US_ASCII)
      offsets[contentObj] = out.size()
      writeAscii("$contentObj 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
      out.write(contentBytes)
      writeAscii("\nendstream\nendobj\n")
    }

    // xref
    val xrefOffset = out.size()
    writeAscii("xref\n0 ${totalObjects + 1}\n0000000000 65535 f \n")
    for (objNum in 1..totalObjects) {
      writeAscii("${offsets[objNum].toString().padStart(10, '0')} 00000 n \n")
    }
    writeAscii("trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")

    return out.toByteArray()
  }

  private data class PdfPageImage(
    val jpeg: ByteArray,
    val pxWidth: Int,
    val pxHeight: Int,
    val ptWidth: Int,
    val ptHeight: Int
  )
}
