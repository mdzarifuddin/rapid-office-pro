package top.teamaos.pdfreader.core;

import io.legere.pdfiumandroid.PdfPage;
import io.legere.pdfiumandroid.core.unlocked.PdfPageU;

/**
 * Reaches the native page handle behind a {@link PdfPage}.
 *
 * <p>Written in Java on purpose. The accessor is {@code internal} in Kotlin, which the Kotlin
 * compiler will not let another module call, but {@code internal} compiles to a public method with
 * a mangled name — and Java has no notion of Kotlin's module visibility, so it can simply call it.
 * That avoids reflection, and it fails at compile time rather than at runtime if the library ever
 * renames it.
 *
 * <p>The pointer is only valid while the {@link PdfPage} is open, so callers must not hold on to it.
 */
public final class PdfPointers {

    private PdfPointers() {
    }

    // Note: there is deliberately no documentPointer() here. PdfDocumentU.getMNativeDocPtr()
    // looks like an FPDF_DOCUMENT but is not one — the wrapper keeps its own structure behind it,
    // and handing that value to pdfium's document APIs aborts the process. Anything needing a real
    // document handle opens the file itself; see PdfNative.extractPages.

    /** Native FPDF_PAGE handle for {@code page}, or 0 if it cannot be reached. */
    public static long pagePointer(PdfPage page) {
        if (page == null) {
            return 0L;
        }
        try {
            PdfPageU unlocked = page.getPage$pdfiumandroid();
            return unlocked == null ? 0L : unlocked.getPagePtr();
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
