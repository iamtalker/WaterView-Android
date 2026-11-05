/*
 * Runs only where ES modules are not supported at all, which is Chromium below
 * 61, far under the pdf.js floor, and where pdf.mjs is never fetched.
 * Only reachable when Kotlin could not read the WebView version, because when it
 * can, the check in pdf-setup.js has already said the same thing with the numbers
 * filled in. Six lines of belt and braces that any module-capable engine skips.
 */
if (!vwPdfBlocked) vwNeedsNewerWebView("PDFs", null, null, vwParams.get("locked"));
