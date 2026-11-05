package kr.iamtalker.waterview

/**
 * The Content Security Policy every viewer page runs under.
 *
 * A document is untrusted input to a renderer that was not written with attackers in
 * mind, and two of the vendored ones write document text into the page as markup:
 * PPTXjs puts slide text and link tooltips through jQuery's html(), and docx-preview
 * copies link targets into href unchecked and draws a Word file's embedded HTML in a
 * frame of its own. Without a policy, script in a crafted file would run as the page.
 * What stopped it reaching anything was the missing INTERNET permission and the Kotlin
 * side's refusal to serve anything but the assets and the document; this is a third
 * wall, inside the page, so that script from a document does not run in the first place.
 *
 * Directive by directive:
 * - Scripts only from WaterView's own files. No inline script, no event-handler
 *   attributes, no javascript: URLs, no eval. pdf.js itself has not needed eval since it
 *   began drawing glyphs as Path2D objects. 'wasm-unsafe-eval' allows compiling
 *   WebAssembly and nothing else, which the pdf.js worker does twice: for its JPEG 2000
 *   and JBIG2 decoders, and for each PostScript function a PDF carries, which it turns
 *   into a small module on the spot.
 * - Styles from WaterView's files and inline, because docx-preview, PPTXjs, SheetJS and
 *   pdf.js's text layer all write style attributes. A style cannot reach anything
 *   either: every url() in one is held to img-src and font-src.
 * - Images, fonts and media from the page's own host, or from data: and blob: URLs,
 *   which are bytes the page already holds. That covers the document at /doc/ and the
 *   pictures, fonts and clips the renderers unpack from it.
 * - Fetches and workers only to the page's own host: the document, the pdf.js worker,
 *   its character maps and decoders.
 * - No frames, plugins, form submissions or base URL. docx-preview's frame for embedded
 *   HTML is a srcdoc one, which frame-src does not govern: it inherits this policy
 *   instead, and docx.js sandboxes it besides.
 *
 * Carried twice. Every page names it in a meta tag, so the test server, which serves the
 * files as they are, enforces the same policy the app does. [ViewerActivity] also sends it
 * as a header on every asset it serves, which is the only way it reaches the pdf.js
 * worker: a worker takes its policy from its own script's response, not from the page.
 * ViewerPolicyTest holds the two to the same text.
 */
internal object ViewerPolicy {
    const val CSP =
        "default-src 'none'; " +
            "script-src 'self' 'wasm-unsafe-eval'; " +
            "style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; " +
            "font-src 'self' data: blob:; " +
            "media-src 'self' data: blob:; " +
            "connect-src 'self'; " +
            "worker-src 'self'; " +
            "frame-src 'none'; " +
            "object-src 'none'; " +
            "form-action 'none'; " +
            "base-uri 'none'"
}
