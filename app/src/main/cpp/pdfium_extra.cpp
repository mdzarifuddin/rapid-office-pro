// A very small bridge to the parts of pdfium that io.legere:pdfiumandroid does not expose.
//
// That library wraps rendering and reading thoroughly, but has no setter for page rotation — only
// FPDFPage_GetRotation. Rotating a page and saving it back is the one editing feature this reader
// needs, so the handful of missing entry points are reached directly here.
//
// The symbols are resolved with dlsym rather than linked against, deliberately: libpdfium.so is
// already loaded into the process by pdfiumandroid before any of this runs, so there is nothing to
// link against at build time and no second copy of a 5 MB library to ship per ABI.

#include <jni.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

namespace {

using FpdfPage = void *;
using FpdfDocument = void *;

// pdfium's file-writer callback block. The struct layout has to match exactly: pdfium passes a
// pointer to it back into WriteBlock, which lets us hang the destination FILE* off the end.
struct FpdfFileWrite {
    int version;
    int (*WriteBlock)(FpdfFileWrite *self, const void *data, unsigned long size);
};

struct FileWriter {
    FpdfFileWrite base;
    std::FILE *file;
};

// pdfium's reader callback block, for documents that are not reachable by path.
struct FpdfFileAccess {
    unsigned long m_FileLen;
    int (*m_GetBlock)(void *param, unsigned long position, unsigned char *buffer, unsigned long size);
    void *m_Param;
};

struct FdReader {
    FpdfFileAccess base;
    int fd;
};

/**
 * Read a block straight out of a file descriptor.
 *
 * pread rather than read: pdfium asks for blocks in whatever order it likes, and the descriptor
 * belongs to the open document, which is being read from at the same time on another thread.
 * Moving its file offset would corrupt both.
 */
int readFromDescriptor(void *param, unsigned long position, unsigned char *buffer, unsigned long size) {
    auto *reader = static_cast<FdReader *>(param);
    if (reader == nullptr || reader->fd < 0 || buffer == nullptr) {
        return 0;
    }
    unsigned long done = 0;
    while (done < size) {
        ssize_t got = pread(reader->fd, buffer + done, size - done, (off_t) (position + done));
        if (got <= 0) {
            return 0;
        }
        done += (unsigned long) got;
    }
    return 1;
}

int writeToFile(FpdfFileWrite *self, const void *data, unsigned long size) {
    auto *writer = reinterpret_cast<FileWriter *>(self);
    if (writer->file == nullptr || data == nullptr) {
        return 0;
    }
    return std::fwrite(data, 1, size, writer->file) == size ? 1 : 0;
}

using SetRotationFn = void (*)(FpdfPage, int);
using GetRotationFn = int (*)(FpdfPage);
using SetBoxFn = void (*)(FpdfPage, float, float, float, float);
using GetBoxFn = int (*)(FpdfPage, float *, float *, float *, float *);
using GenerateContentFn = int (*)(FpdfPage);
using CreateDocumentFn = FpdfDocument (*)();
using CloseDocumentFn = void (*)(FpdfDocument);
using ImportPagesFn = int (*)(FpdfDocument, FpdfDocument, const char *, int);
using SaveAsCopyFn = int (*)(FpdfDocument, FpdfFileWrite *, unsigned long);
using LoadDocumentFn = FpdfDocument (*)(const char *, const char *);
using LoadCustomFn = FpdfDocument (*)(FpdfFileAccess *, const char *);
using GetPageCountFn = int (*)(FpdfDocument);
using LoadPageFn = FpdfPage (*)(FpdfDocument, int);
using ClosePageFn = void (*)(FpdfPage);
using FpdfPageObject = void *;
using CreatePathFn = FpdfPageObject (*)(float, float);
using PathLineToFn = int (*)(FpdfPageObject, float, float);
using PathDrawModeFn = int (*)(FpdfPageObject, int, int);
using SetStrokeColorFn = int (*)(FpdfPageObject, unsigned int, unsigned int, unsigned int, unsigned int);
using SetStrokeWidthFn = int (*)(FpdfPageObject, float);
using InsertObjectFn = void (*)(FpdfPage, FpdfPageObject);
using DestroyObjectFn = void (*)(FpdfPageObject);
using FpdfLink = void *;
using FpdfDest = void *;
using FpdfAction = void *;
using LinkEnumerateFn = int (*)(FpdfPage, int *, FpdfLink *);
using LinkRectFn = int (*)(FpdfLink, float *);
using LinkGetDestFn = FpdfDest (*)(FpdfDocument, FpdfLink);
using LinkGetActionFn = FpdfAction (*)(FpdfLink);
using ActionTypeFn = unsigned long (*)(FpdfAction);
using ActionDestFn = FpdfDest (*)(FpdfDocument, FpdfAction);
using ActionUriFn = unsigned long (*)(FpdfDocument, FpdfAction, void *, unsigned long);
using DestPageIndexFn = int (*)(FpdfDocument, FpdfDest);

struct PdfiumSymbols {
    SetRotationFn setRotation = nullptr;
    GetRotationFn getRotation = nullptr;
    SetBoxFn setCropBox = nullptr;
    GetBoxFn getCropBox = nullptr;
    GetBoxFn getMediaBox = nullptr;
    GenerateContentFn generateContent = nullptr;
    CreateDocumentFn createDocument = nullptr;
    CloseDocumentFn closeDocument = nullptr;
    ImportPagesFn importPages = nullptr;
    SaveAsCopyFn saveAsCopy = nullptr;
    LoadDocumentFn loadDocument = nullptr;
    LoadCustomFn loadCustom = nullptr;
    GetPageCountFn getPageCount = nullptr;
    LoadPageFn loadPage = nullptr;
    ClosePageFn closePage = nullptr;
    CreatePathFn createPath = nullptr;
    PathLineToFn pathLineTo = nullptr;
    PathDrawModeFn pathDrawMode = nullptr;
    SetStrokeColorFn setStrokeColor = nullptr;
    SetStrokeWidthFn setStrokeWidth = nullptr;
    InsertObjectFn insertObject = nullptr;
    DestroyObjectFn destroyObject = nullptr;
    bool canDraw = false;
    LinkEnumerateFn linkEnumerate = nullptr;
    LinkRectFn linkRect = nullptr;
    LinkGetDestFn linkGetDest = nullptr;
    LinkGetActionFn linkGetAction = nullptr;
    ActionTypeFn actionType = nullptr;
    ActionDestFn actionDest = nullptr;
    ActionUriFn actionUri = nullptr;
    DestPageIndexFn destPageIndex = nullptr;
    bool canReadLinks = false;
    bool resolved = false;
    bool canExtract = false;
    bool canCrop = false;
};

PdfiumSymbols g_symbols;
std::once_flag g_resolveOnce;

void resolveSymbols() {
    // RTLD_NOLOAD first: the library is already mapped, and we only want its handle. Falling back
    // to a normal dlopen covers the case where this is somehow called before pdfium is touched.
    void *handle = dlopen("libpdfium.so", RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        handle = dlopen("libpdfium.so", RTLD_NOW);
    }
    if (handle == nullptr) {
        return;
    }
    g_symbols.setRotation = reinterpret_cast<SetRotationFn>(dlsym(handle, "FPDFPage_SetRotation"));
    g_symbols.getRotation = reinterpret_cast<GetRotationFn>(dlsym(handle, "FPDFPage_GetRotation"));
    g_symbols.setCropBox = reinterpret_cast<SetBoxFn>(dlsym(handle, "FPDFPage_SetCropBox"));
    g_symbols.getCropBox = reinterpret_cast<GetBoxFn>(dlsym(handle, "FPDFPage_GetCropBox"));
    g_symbols.getMediaBox = reinterpret_cast<GetBoxFn>(dlsym(handle, "FPDFPage_GetMediaBox"));
    g_symbols.generateContent =
            reinterpret_cast<GenerateContentFn>(dlsym(handle, "FPDFPage_GenerateContent"));
    g_symbols.createDocument =
            reinterpret_cast<CreateDocumentFn>(dlsym(handle, "FPDF_CreateNewDocument"));
    g_symbols.closeDocument =
            reinterpret_cast<CloseDocumentFn>(dlsym(handle, "FPDF_CloseDocument"));
    g_symbols.importPages = reinterpret_cast<ImportPagesFn>(dlsym(handle, "FPDF_ImportPages"));
    g_symbols.saveAsCopy = reinterpret_cast<SaveAsCopyFn>(dlsym(handle, "FPDF_SaveAsCopy"));
    g_symbols.loadDocument = reinterpret_cast<LoadDocumentFn>(dlsym(handle, "FPDF_LoadDocument"));
    g_symbols.loadCustom =
            reinterpret_cast<LoadCustomFn>(dlsym(handle, "FPDF_LoadCustomDocument"));
    g_symbols.getPageCount = reinterpret_cast<GetPageCountFn>(dlsym(handle, "FPDF_GetPageCount"));
    g_symbols.loadPage = reinterpret_cast<LoadPageFn>(dlsym(handle, "FPDF_LoadPage"));
    g_symbols.closePage = reinterpret_cast<ClosePageFn>(dlsym(handle, "FPDF_ClosePage"));
    g_symbols.createPath = reinterpret_cast<CreatePathFn>(dlsym(handle, "FPDFPageObj_CreateNewPath"));
    g_symbols.pathLineTo = reinterpret_cast<PathLineToFn>(dlsym(handle, "FPDFPath_LineTo"));
    g_symbols.pathDrawMode = reinterpret_cast<PathDrawModeFn>(dlsym(handle, "FPDFPath_SetDrawMode"));
    g_symbols.setStrokeColor =
            reinterpret_cast<SetStrokeColorFn>(dlsym(handle, "FPDFPageObj_SetStrokeColor"));
    g_symbols.setStrokeWidth =
            reinterpret_cast<SetStrokeWidthFn>(dlsym(handle, "FPDFPageObj_SetStrokeWidth"));
    g_symbols.insertObject = reinterpret_cast<InsertObjectFn>(dlsym(handle, "FPDFPage_InsertObject"));
    g_symbols.destroyObject = reinterpret_cast<DestroyObjectFn>(dlsym(handle, "FPDFPageObj_Destroy"));
    g_symbols.linkEnumerate = reinterpret_cast<LinkEnumerateFn>(dlsym(handle, "FPDFLink_Enumerate"));
    g_symbols.linkRect = reinterpret_cast<LinkRectFn>(dlsym(handle, "FPDFLink_GetAnnotRect"));
    g_symbols.linkGetDest = reinterpret_cast<LinkGetDestFn>(dlsym(handle, "FPDFLink_GetDest"));
    g_symbols.linkGetAction = reinterpret_cast<LinkGetActionFn>(dlsym(handle, "FPDFLink_GetAction"));
    g_symbols.actionType = reinterpret_cast<ActionTypeFn>(dlsym(handle, "FPDFAction_GetType"));
    g_symbols.actionDest = reinterpret_cast<ActionDestFn>(dlsym(handle, "FPDFAction_GetDest"));
    g_symbols.actionUri = reinterpret_cast<ActionUriFn>(dlsym(handle, "FPDFAction_GetURIPath"));
    g_symbols.destPageIndex =
            reinterpret_cast<DestPageIndexFn>(dlsym(handle, "FPDFDest_GetDestPageIndex"));

    g_symbols.resolved = g_symbols.setRotation != nullptr;
    g_symbols.canExtract = g_symbols.createDocument != nullptr &&
                           g_symbols.closeDocument != nullptr &&
                           g_symbols.importPages != nullptr &&
                           g_symbols.saveAsCopy != nullptr &&
                           (g_symbols.loadDocument != nullptr || g_symbols.loadCustom != nullptr);
    g_symbols.canDraw = g_symbols.createPath != nullptr && g_symbols.pathLineTo != nullptr &&
                        g_symbols.pathDrawMode != nullptr && g_symbols.setStrokeColor != nullptr &&
                        g_symbols.setStrokeWidth != nullptr && g_symbols.insertObject != nullptr &&
                        g_symbols.generateContent != nullptr;
    g_symbols.canReadLinks = g_symbols.linkEnumerate != nullptr && g_symbols.linkRect != nullptr &&
                             g_symbols.loadPage != nullptr && g_symbols.closePage != nullptr &&
                             (g_symbols.loadDocument != nullptr || g_symbols.loadCustom != nullptr);
    g_symbols.canCrop = g_symbols.setCropBox != nullptr &&
                        (g_symbols.getCropBox != nullptr || g_symbols.getMediaBox != nullptr);
}

const PdfiumSymbols &symbols() {
    std::call_once(g_resolveOnce, resolveSymbols);
    return g_symbols;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeIsAvailable(JNIEnv *, jobject) {
    return symbols().resolved ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeSetPageRotation(
        JNIEnv *, jobject, jlong pagePtr, jint rotation) {
    const PdfiumSymbols &api = symbols();
    if (!api.resolved || pagePtr == 0) {
        return JNI_FALSE;
    }
    // pdfium counts rotation in quarter turns: 0, 1, 2, 3.
    api.setRotation(reinterpret_cast<FpdfPage>(pagePtr), rotation & 3);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeGetPageRotation(JNIEnv *, jobject, jlong pagePtr) {
    const PdfiumSymbols &api = symbols();
    if (api.getRotation == nullptr || pagePtr == 0) {
        return 0;
    }
    return api.getRotation(reinterpret_cast<FpdfPage>(pagePtr));
}

JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeGeneratePageContent(JNIEnv *, jobject, jlong pagePtr) {
    const PdfiumSymbols &api = symbols();
    if (api.generateContent == nullptr || pagePtr == 0) {
        return JNI_FALSE;
    }
    return api.generateContent(reinterpret_cast<FpdfPage>(pagePtr)) != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeCanExtract(JNIEnv *, jobject) {
    return symbols().canExtract ? JNI_TRUE : JNI_FALSE;
}

/**
 * Turn each of the named pages into two, side by side, and write the result out.
 *
 * A book scanned two facing pages at a time is one wide page per sheet, and reading it on a phone
 * means half a page of text at a readable size. This makes the split real rather than a viewing
 * trick: the source page is imported twice and each copy is cropped to one half, so what comes out
 * is an ordinary PDF with twice as many ordinary pages, which scrolls, searches and prints like
 * any other one.
 *
 * The halves are worked out in the page's own unrotated space, because a scan is very often a
 * landscape sheet stored as a rotated portrait one, and on those the visible left half is the
 * stored bottom half.
 */
static bool splitInto(const PdfiumSymbols &api, FpdfDocument sourceDocument, FpdfDocument destination,
                      const std::string &splitFlags, bool rightFirst, const char *path) {
    if (api.importPages(destination, sourceDocument, nullptr, 0) == 0) {
        return false;
    }
    int pages = api.getPageCount(sourceDocument);
    if (pages <= 0) {
        return false;
    }

    // Duplicate from the back, so the positions of the pages still to do do not move.
    for (int index = pages - 1; index >= 0; --index) {
        if (index >= (int) splitFlags.size() || splitFlags[index] != '1') {
            continue;
        }
        std::string one = std::to_string(index + 1);
        if (api.importPages(destination, sourceDocument, one.c_str(), index + 1) == 0) {
            return false;
        }
    }

    int destinationIndex = 0;
    for (int index = 0; index < pages; ++index) {
        bool split = index < (int) splitFlags.size() && splitFlags[index] == '1';
        int copies = split ? 2 : 1;
        for (int copy = 0; copy < copies; ++copy) {
            if (split && api.loadPage != nullptr && api.closePage != nullptr) {
                FpdfPage page = api.loadPage(destination, destinationIndex);
                if (page != nullptr) {
                    float left = 0, bottom = 0, right = 0, top = 0;
                    bool ok = api.getCropBox != nullptr &&
                              api.getCropBox(page, &left, &bottom, &right, &top) != 0;
                    if (!ok && api.getMediaBox != nullptr) {
                        ok = api.getMediaBox(page, &left, &bottom, &right, &top) != 0;
                    }
                    if (ok && right > left && top > bottom) {
                        bool firstHalf = (copy == 0) != rightFirst;
                        float width = right - left;
                        float height = top - bottom;
                        int rotation = api.getRotation != nullptr ? (api.getRotation(page) & 3) : 0;
                        float newLeft = left, newRight = right, newBottom = bottom, newTop = top;
                        switch (rotation) {
                            case 1:
                                if (firstHalf) { newTop = bottom + height / 2; }
                                else { newBottom = bottom + height / 2; }
                                break;
                            case 2:
                                if (firstHalf) { newLeft = left + width / 2; }
                                else { newRight = left + width / 2; }
                                break;
                            case 3:
                                if (firstHalf) { newBottom = bottom + height / 2; }
                                else { newTop = bottom + height / 2; }
                                break;
                            default:
                                if (firstHalf) { newRight = left + width / 2; }
                                else { newLeft = left + width / 2; }
                                break;
                        }
                        api.setCropBox(page, newLeft, newBottom, newRight, newTop);
                    }
                    api.closePage(page);
                }
            }
            destinationIndex++;
        }
    }

    std::FILE *file = std::fopen(path, "wb");
    if (file == nullptr) {
        return false;
    }
    FileWriter writer{};
    writer.base.version = 1;
    writer.base.WriteBlock = &writeToFile;
    writer.file = file;
    bool saved = api.saveAsCopy(destination, &writer.base, 0) != 0;
    std::fclose(file);
    return saved;
}

JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeSplitPages(
        JNIEnv *env, jobject, jint fd, jstring sourcePath, jstring passwordText,
        jstring flagsText, jboolean rightFirst, jstring outputPath) {
    const PdfiumSymbols &api = symbols();
    if (!api.canExtract || !api.canCrop || flagsText == nullptr || outputPath == nullptr ||
        api.getPageCount == nullptr) {
        return JNI_FALSE;
    }

    const char *password = passwordText == nullptr ? nullptr : env->GetStringUTFChars(passwordText, nullptr);
    const char *flags = env->GetStringUTFChars(flagsText, nullptr);
    const char *path = env->GetStringUTFChars(outputPath, nullptr);
    const char *source = sourcePath == nullptr ? nullptr : env->GetStringUTFChars(sourcePath, nullptr);
    bool ok = false;

    if (flags != nullptr && path != nullptr) {
        FdReader reader{};
        FpdfDocument sourceDocument = nullptr;
        if (source != nullptr && api.loadDocument != nullptr) {
            sourceDocument = api.loadDocument(source, password);
        }
        if (sourceDocument == nullptr && fd >= 0 && api.loadCustom != nullptr) {
            struct stat info{};
            if (fstat(fd, &info) == 0 && info.st_size > 0) {
                reader.base.m_FileLen = (unsigned long) info.st_size;
                reader.base.m_GetBlock = &readFromDescriptor;
                reader.base.m_Param = &reader;
                reader.fd = fd;
                sourceDocument = api.loadCustom(&reader.base, password);
            }
        }
        if (sourceDocument != nullptr) {
            FpdfDocument destination = api.createDocument();
            if (destination != nullptr) {
                ok = splitInto(api, sourceDocument, destination, std::string(flags),
                               rightFirst == JNI_TRUE, path);
                api.closeDocument(destination);
            }
            api.closeDocument(sourceDocument);
        }
    }

    if (password != nullptr) env->ReleaseStringUTFChars(passwordText, password);
    if (flags != nullptr) env->ReleaseStringUTFChars(flagsText, flags);
    if (path != nullptr) env->ReleaseStringUTFChars(outputPath, path);
    if (source != nullptr) env->ReleaseStringUTFChars(sourcePath, source);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * A second, private handle on the document, opened only to read its links.
 *
 * The wrapper library this app renders through does not hand out a real FPDF_DOCUMENT, and every
 * link question needs one: a link's destination is a name that only the document can resolve into
 * a page number. So the file is opened once more here, kept for as long as the reader has it open,
 * and used for nothing else. It costs a cross-reference parse and no page memory.
 */
struct LinkReader {
    FpdfDocument document = nullptr;
    FdReader reader{};
};

JNIEXPORT jlong JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeOpenLinkReader(
        JNIEnv *env, jobject, jint fd, jstring sourcePath, jstring passwordText) {
    const PdfiumSymbols &api = symbols();
    if (!api.canReadLinks) {
        return 0;
    }
    const char *password = passwordText == nullptr ? nullptr : env->GetStringUTFChars(passwordText, nullptr);
    const char *source = sourcePath == nullptr ? nullptr : env->GetStringUTFChars(sourcePath, nullptr);

    auto *holder = new LinkReader();
    if (source != nullptr && api.loadDocument != nullptr) {
        holder->document = api.loadDocument(source, password);
    }
    if (holder->document == nullptr && fd >= 0 && api.loadCustom != nullptr) {
        struct stat info{};
        if (fstat(fd, &info) == 0 && info.st_size > 0) {
            holder->reader.base.m_FileLen = (unsigned long) info.st_size;
            holder->reader.base.m_GetBlock = &readFromDescriptor;
            holder->reader.base.m_Param = &holder->reader;
            holder->reader.fd = fd;
            holder->document = api.loadCustom(&holder->reader.base, password);
        }
    }

    if (password != nullptr) env->ReleaseStringUTFChars(passwordText, password);
    if (source != nullptr) env->ReleaseStringUTFChars(sourcePath, source);

    if (holder->document == nullptr) {
        delete holder;
        return 0;
    }
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT void JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeCloseLinkReader(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) {
        return;
    }
    auto *holder = reinterpret_cast<LinkReader *>(handle);
    const PdfiumSymbols &api = symbols();
    if (holder->document != nullptr && api.closeDocument != nullptr) {
        api.closeDocument(holder->document);
    }
    delete holder;
}

/**
 * Every link on one page, as "left,bottom,right,top|P12" for a page jump or "…|Uhttps://…".
 *
 * One flat string array rather than a structure per link: the whole point of crossing into native
 * code here is to answer one question per page, and building a Java object graph across JNI for
 * something the caller immediately turns back into its own type would be work for its own sake.
 */
JNIEXPORT jobjectArray JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeLinksOnPage(
        JNIEnv *env, jobject, jlong handle, jint pageIndex) {
    const PdfiumSymbols &api = symbols();
    if (handle == 0 || !api.canReadLinks || pageIndex < 0) {
        return nullptr;
    }
    auto *holder = reinterpret_cast<LinkReader *>(handle);
    FpdfPage page = api.loadPage(holder->document, pageIndex);
    if (page == nullptr) {
        return nullptr;
    }

    std::vector<std::string> entries;
    int position = 0;
    FpdfLink link = nullptr;
    while (api.linkEnumerate(page, &position, &link) != 0 && entries.size() < 512) {
        if (link == nullptr) {
            continue;
        }
        float rect[4] = {0, 0, 0, 0};
        if (api.linkRect == nullptr || api.linkRect(link, rect) == 0) {
            continue;
        }

        // A destination may be attached to the link directly or sit behind a GoTo action; a named
        // destination only ever appears the second way, which is why both are tried.
        int destination = -1;
        if (api.linkGetDest != nullptr && api.destPageIndex != nullptr) {
            FpdfDest dest = api.linkGetDest(holder->document, link);
            if (dest != nullptr) {
                destination = api.destPageIndex(holder->document, dest);
            }
        }
        std::string uri;
        if (api.linkGetAction != nullptr) {
            FpdfAction action = api.linkGetAction(link);
            if (action != nullptr) {
                unsigned long type = api.actionType != nullptr ? api.actionType(action) : 0;
                if (destination < 0 && api.actionDest != nullptr && api.destPageIndex != nullptr) {
                    FpdfDest dest = api.actionDest(holder->document, action);
                    if (dest != nullptr) {
                        destination = api.destPageIndex(holder->document, dest);
                    }
                }
                // 3 is PDFACTION_URI in pdfium's own numbering.
                if (type == 3 && api.actionUri != nullptr) {
                    unsigned long needed = api.actionUri(holder->document, action, nullptr, 0);
                    if (needed > 1 && needed < 4096) {
                        std::string buffer(needed, '\0');
                        api.actionUri(holder->document, action, &buffer[0], needed);
                        // The reported length includes the terminating byte.
                        buffer.resize(needed - 1);
                        uri = buffer;
                    }
                }
            }
        }
        if (destination < 0 && uri.empty()) {
            continue;
        }
        char header[96];
        std::snprintf(header, sizeof(header), "%.2f,%.2f,%.2f,%.2f|", rect[0], rect[1], rect[2], rect[3]);
        std::string entry(header);
        if (!uri.empty()) {
            entry += "U" + uri;
        } else {
            entry += "P" + std::to_string(destination);
        }
        entries.push_back(entry);
    }
    api.closePage(page);

    if (entries.empty()) {
        return nullptr;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray((jsize) entries.size(), stringClass, nullptr);
    for (jsize index = 0; index < (jsize) entries.size(); ++index) {
        jstring value = env->NewStringUTF(entries[index].c_str());
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeCanDraw(JNIEnv *, jobject) {
    return symbols().canDraw ? JNI_TRUE : JNI_FALSE;
}

/**
 * Write one brush stroke into a page as a real path object.
 *
 * Until this is called a stroke is only something the app draws on top; afterwards it is part of
 * the page, and every other PDF reader in the world will show it. That is the whole difference
 * between marking up a document for yourself and masking something before sending it on.
 *
 * Points are x, y pairs in page coordinates, which is already the space pdfium's path API works
 * in, so nothing is converted here.
 */
JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeAddStroke(
        JNIEnv *env, jobject, jlong pagePtr, jint colour, jfloat width, jfloatArray pointArray) {
    const PdfiumSymbols &api = symbols();
    if (!api.canDraw || pagePtr == 0 || pointArray == nullptr) {
        return JNI_FALSE;
    }
    jsize count = env->GetArrayLength(pointArray);
    if (count < 2 || (count % 2) != 0) {
        return JNI_FALSE;
    }
    std::vector<float> points((size_t) count);
    env->GetFloatArrayRegion(pointArray, 0, count, points.data());

    FpdfPageObject path = api.createPath(points[0], points[1]);
    if (path == nullptr) {
        return JNI_FALSE;
    }
    if (count == 2) {
        // A dot: a hairline back to where it started, drawn with a round cap by the stroke width.
        api.pathLineTo(path, points[0] + 0.01f, points[1]);
    } else {
        for (jsize index = 2; index + 1 < count; index += 2) {
            api.pathLineTo(path, points[index], points[index + 1]);
        }
    }

    unsigned int alpha = (unsigned int) ((colour >> 24) & 0xFF);
    unsigned int red = (unsigned int) ((colour >> 16) & 0xFF);
    unsigned int green = (unsigned int) ((colour >> 8) & 0xFF);
    unsigned int blue = (unsigned int) (colour & 0xFF);
    api.setStrokeColor(path, red, green, blue, alpha);
    api.setStrokeWidth(path, width);
    // Stroke, do not fill: a mask is a thick line, and filling it would flood the shape it
    // happens to enclose when someone draws a loop.
    api.pathDrawMode(path, 0, 1);
    api.insertObject(reinterpret_cast<FpdfPage>(pagePtr), path);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeCanCrop(JNIEnv *, jobject) {
    return symbols().canCrop ? JNI_TRUE : JNI_FALSE;
}

/**
 * The box a page is drawn inside, as {left, bottom, right, top} in PDF points.
 *
 * The crop box is what a viewer shows, and it is optional: a page without one is shown at its
 * media box instead, so that is the fallback. Returns null if neither can be read.
 */
JNIEXPORT jfloatArray JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeGetVisibleBox(JNIEnv *env, jobject, jlong pagePtr) {
    const PdfiumSymbols &api = symbols();
    if (pagePtr == 0) {
        return nullptr;
    }
    auto page = reinterpret_cast<FpdfPage>(pagePtr);
    float left = 0, bottom = 0, right = 0, top = 0;
    bool ok = api.getCropBox != nullptr && api.getCropBox(page, &left, &bottom, &right, &top) != 0;
    if (!ok && api.getMediaBox != nullptr) {
        ok = api.getMediaBox(page, &left, &bottom, &right, &top) != 0;
    }
    if (!ok || right <= left || top <= bottom) {
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(4);
    if (result == nullptr) {
        return nullptr;
    }
    const float values[4] = {left, bottom, right, top};
    env->SetFloatArrayRegion(result, 0, 4, values);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeSetCropBox(
        JNIEnv *, jobject, jlong pagePtr, jfloat left, jfloat bottom, jfloat right, jfloat top) {
    const PdfiumSymbols &api = symbols();
    if (!api.canCrop || pagePtr == 0 || right <= left || top <= bottom) {
        return JNI_FALSE;
    }
    api.setCropBox(reinterpret_cast<FpdfPage>(pagePtr), left, bottom, right, top);
    return JNI_TRUE;
}

/**
 * Build a new PDF from a subset of an existing one and write it to a path.
 *
 * The source is opened here from [sourcePath] rather than reusing the handle the Kotlin wrapper
 * holds. That handle is not a bare FPDF_DOCUMENT — the wrapper keeps its own struct behind it — and
 * passing it to FPDF_ImportPages walked off into pdfium and aborted the process. Opening the file
 * again costs a memory map and an xref parse, which is nothing next to the certainty of owning a
 * pointer we created ourselves.
 *
 * [sourcePath] may be a plain path or `/proc/self/fd/N` for a descriptor, which is how documents
 * that arrived through a content provider are reached without copying them.
 *
 * `ranges` is pdfium's own page-range syntax, one-based, e.g. "1,4,7-9".
 */
/**
 * Build a new PDF from a subset of an open document and write it out.
 *
 * Shared by both entry points below; [sourceDocument] is already loaded and is closed by the
 * caller, because how it was loaded is exactly what the two entry points differ on.
 */
static bool writeSubset(const PdfiumSymbols &api, FpdfDocument sourceDocument,
                        const char *ranges, const char *path) {
    if (sourceDocument == nullptr || ranges == nullptr || path == nullptr) {
        return false;
    }
    bool ok = false;
    FpdfDocument destination = api.createDocument();
    if (destination != nullptr) {
        if (api.importPages(destination, sourceDocument, ranges, 0) != 0) {
            std::FILE *file = std::fopen(path, "wb");
            if (file != nullptr) {
                FileWriter writer{};
                writer.base.version = 1;
                writer.base.WriteBlock = &writeToFile;
                writer.file = file;
                ok = api.saveAsCopy(destination, &writer.base, 0) != 0;
                std::fclose(file);
            }
        }
        api.closeDocument(destination);
    }
    return ok;
}

/**
 * Extract pages from a document reached only by a file descriptor.
 *
 * This is the path for anything opened through the system file picker. Such a document has no
 * filename this process is allowed to open — /proc/self/fd/N looks like one and is not, which is
 * why extraction used to fail on exactly those files — so pdfium is handed a reader that pulls
 * blocks straight out of the descriptor instead.
 */
JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeExtractPagesFd(
        JNIEnv *env, jobject, jint fd, jstring passwordText, jstring rangesText, jstring outputPath) {
    const PdfiumSymbols &api = symbols();
    if (!api.canExtract || api.loadCustom == nullptr || fd < 0 ||
        rangesText == nullptr || outputPath == nullptr) {
        return JNI_FALSE;
    }
    struct stat info {};
    if (fstat(fd, &info) != 0 || info.st_size <= 0) {
        return JNI_FALSE;
    }

    const char *password = passwordText == nullptr ? nullptr : env->GetStringUTFChars(passwordText, nullptr);
    const char *ranges = env->GetStringUTFChars(rangesText, nullptr);
    const char *path = env->GetStringUTFChars(outputPath, nullptr);
    bool ok = false;

    if (ranges != nullptr && path != nullptr) {
        FdReader reader{};
        reader.base.m_FileLen = (unsigned long) info.st_size;
        reader.base.m_GetBlock = &readFromDescriptor;
        reader.base.m_Param = &reader;
        reader.fd = fd;
        FpdfDocument sourceDocument = api.loadCustom(&reader.base, password);
        if (sourceDocument != nullptr) {
            ok = writeSubset(api, sourceDocument, ranges, path);
            api.closeDocument(sourceDocument);
        }
    }

    if (password != nullptr) env->ReleaseStringUTFChars(passwordText, password);
    if (ranges != nullptr) env->ReleaseStringUTFChars(rangesText, ranges);
    if (path != nullptr) env->ReleaseStringUTFChars(outputPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_top_teamaos_pdfreader_core_PdfNative_nativeExtractPages(
        JNIEnv *env, jobject, jstring sourcePath, jstring passwordText, jstring rangesText,
        jstring outputPath) {
    const PdfiumSymbols &api = symbols();
    if (!api.canExtract || sourcePath == nullptr || rangesText == nullptr || outputPath == nullptr) {
        return JNI_FALSE;
    }

    const char *source = env->GetStringUTFChars(sourcePath, nullptr);
    const char *password = passwordText == nullptr ? nullptr : env->GetStringUTFChars(passwordText, nullptr);
    const char *ranges = env->GetStringUTFChars(rangesText, nullptr);
    const char *path = env->GetStringUTFChars(outputPath, nullptr);
    bool ok = false;

    if (source != nullptr && ranges != nullptr && path != nullptr && api.loadDocument != nullptr) {
        FpdfDocument sourceDocument = api.loadDocument(source, password);
        if (sourceDocument != nullptr) {
            ok = writeSubset(api, sourceDocument, ranges, path);
            api.closeDocument(sourceDocument);
        }
    }

    if (source != nullptr) env->ReleaseStringUTFChars(sourcePath, source);
    if (password != nullptr) env->ReleaseStringUTFChars(passwordText, password);
    if (ranges != nullptr) env->ReleaseStringUTFChars(rangesText, ranges);
    if (path != nullptr) env->ReleaseStringUTFChars(outputPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
