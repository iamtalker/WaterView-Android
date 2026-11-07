var content = document.getElementById("content");
/* Prose reads the way most of its letters do, decided once, from the first page that has any,
   so showing more never turns the file round. Code, data, subtitles and playlists stay left to
   right, which their layout depends on. */
var findsDirection = ["", "txt", "log", "nfo"].indexOf(vwExt) >= 0;
// An engine below Chromium 64 can't tell scripts apart, so there the first letter decides.
if (findsDirection && vwMostlyRightToLeft("a") === null) {
  content.dir = "auto";
  findsDirection = false;
}
var more = document.getElementById("more");
var moreNote = document.getElementById("moreNote");
var moreBtn = document.getElementById("moreBtn");
var doc = null;
var shown = 0;
var firstPage = true;
// Whether every page has been read: nothing further to go to past what is shown
var finished = false;
// Whether line breaks are joined into paragraphs, as the app asks for it in the URL
var reflow = vwParams.get("reflow") === "1";

/* Append rather than reassign textContent: the whole document would otherwise
   be reserialised on every page. */
function showPage() {
  moreBtn.disabled = true;
  return doc.next().then(function (page) {
    var text = page.text;

    /* A .json file is more often than not written with no spacing at all, and
       arrives as one unbroken line. Lay it out only when the entire file arrived
       in the first page and is small enough to draw; formatting one chunk would
       leave the reader a document half laid out and half not. Anything larger
       or invalid is shown as it is. */
    if (firstPage && page.done && text.length <= VW_JSON_MAX && vwExt === "json") {
      var laid = vwFormatJson(text);
      if (laid !== null) text = laid;
    }
    /* Joins the lines of a paragraph, with spaces of the same length as the breaks they replace,
       so the places kept for bookmarks and for reopening still count the same characters. A blank
       line stays as it is. */
    if (reflow) {
      text = text.replace(/(\r?\n\r?\n)|\r?\n/g, function (m, blank) {
        return blank ? blank : (m.length === 2 ? "  " : " ");
      });
    }
    firstPage = false;

    if (findsDirection) {
      // A sample tells as well as the whole page, which can run to megabytes
      var rtl = vwMostlyRightToLeft(text.slice(0, VW_DIRECTION_SAMPLE));
      if (rtl !== null) {
        content.dir = rtl ? "rtl" : "ltr";
        findsDirection = false;
      }
    }

    content.appendChild(document.createTextNode(text));
    shown += page.bytes;
    finished = page.done;
    if (page.done) {
      more.hidden = true;
    } else {
      moreNote.textContent = "이 파일의 앞 " + vwFormatSize(shown) + "을(를) 보여 주고 있습니다.";
      more.hidden = false;
      moreBtn.disabled = false;
    }
  });
}

moreBtn.addEventListener("click", function () {
  showPage().catch(function (e) { vwError("나머지 내용을 읽을 수 없습니다", String(e)); });
});

/* Positions are counted in characters of the text as it is decoded, from its start. The page
   reports the top of the screen as "tx <n>", and goes to one when told "j<n>" (see Marks.kt). */

/* The characters of text from the start of the document up to [node] at [offset], or -1 when
   [node] is not in it. Walked rather than kept, so search's highlights, which split the text
   into more nodes, do not change the count. */
function charsBefore(node, offset) {
  var walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
  var n = 0, here;
  while ((here = walker.nextNode())) {
    if (here === node) return n + offset;
    n += here.nodeValue.length;
  }
  return -1;
}

/* How far down the page the top of the text starts: the title bar's room, and a little more */
function topPad() {
  return (parseFloat(getComputedStyle(document.body).paddingTop) || 0) + 6;
}

/* The character at the top of the screen, or null when none is there to be found */
function topChar() {
  var pos = document.caretRangeFromPoint ? document.caretRangeFromPoint(innerWidth / 2, topPad()) : null;
  if (!pos) return null;
  var at = charsBefore(pos.startContainer, pos.startOffset);
  return at < 0 ? null : at;
}

/* The text from [target] on, about a line long, for a bookmark to be named by. Spaces and line
   breaks are made single spaces, since the app reads the words after the place as one piece. */
function snippetAt(target) {
  var walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
  var n = 0, out = "", here;
  while ((here = walker.nextNode()) && out.length < 40) {
    var len = here.nodeValue.length;
    if (target < n + len) {
      out += here.nodeValue.slice(Math.max(0, target - n));
    }
    n += len;
  }
  return out.replace(/\s+/g, " ").trim().slice(0, 40);
}

var lastTop = -1;
function reportTop() {
  var at = topChar();
  if (at === null || at === lastTop) return;
  // The app's port arrives a moment after the page has loaded, so a report made before then
  // is tried again shortly, for a few seconds at most
  if (vwFind.post("tx " + at + " " + snippetAt(at))) {
    lastTop = at;
    reportTries = 0;
  } else if (reportTries++ < 20) {
    setTimeout(reportTop, 250);
  }
}
var reportTries = 0;

var reportTimer = 0;
addEventListener("scroll", function () {
  clearTimeout(reportTimer);
  reportTimer = setTimeout(reportTop, 300);
}, { passive: true });
/* Scrolling is also checked once a second, since a scroll the page does not announce would
   otherwise leave the place at where it was last reported */
setInterval(reportTop, 1000);
document.addEventListener("visibilitychange", function () {
  if (document.visibilityState === "hidden") reportTop();
});

/* Brings the text at character [target] to the top of the screen, reading more pages first if
   it has not been read yet. Resolves once it is there, or as near as there is to go. */
function goToChar(target) {
  if (!(target >= 0)) return Promise.resolve();
  if (!finished && content.textContent.length <= target) {
    return showPage().then(function () { return goToChar(target); });
  }
  var walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
  var n = 0, node = null, here;
  while ((here = walker.nextNode())) {
    node = here;
    if (target < n + here.nodeValue.length) break;
    n += here.nodeValue.length;
  }
  if (!node) return Promise.resolve();
  var range = document.createRange();
  range.setStart(node, Math.max(0, Math.min(node.nodeValue.length, target - n)));
  range.collapse(true);
  var rects = range.getClientRects();
  var top = rects.length ? rects[0].top : node.parentNode.getBoundingClientRect().top;
  scrollTo(0, pageYOffset + top - topPad());
  reportTop();
  return Promise.resolve();
}

window.vwPageCommand = function (msg) {
  if (msg.charAt(0) === "j") goToChar(parseInt(msg.slice(1), 10));
};

var markAt = parseInt(vwParams.get("mark"), 10);

vwOpenText()
  .then(function (d) {
    doc = d;
    return showPage();
  })
  .then(vwStatusDone)
  .then(function () { return markAt > 0 ? goToChar(markAt) : undefined; })
  // Where the screen starts is known before any scroll, so a bookmark can be put there at once
  .then(function () { reportTop(); })
  .catch(function (e) { vwError("이 파일을 읽을 수 없습니다", String(e)); });
