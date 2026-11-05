"use strict";

/*
 * STL, the file a 3D printer's slicer takes, read for model.html.
 *
 * An STL is a list of triangles and nothing else: no units, no colour, no parts. It comes
 * in two kinds. A binary one is an 80-byte header, a triangle count, and then fifty bytes
 * a triangle: a normal and three corners as little-endian float32s, and two bytes nobody
 * agrees on. A text one says the same in words:
 *
 *   solid name
 *     facet normal 0 0 -1
 *       outer loop
 *         vertex 0 0 0
 *         vertex 10 0 0
 *         vertex 10 10 0
 *       endloop
 *     endfacet
 *   endsolid name
 *
 * The file's own normals are ignored. Plenty of exporters write zeros there, or normals
 * that disagree with the order of the corners, and the corners are what gets drawn, so
 * model.js works each face's normal out from them.
 *
 * Read as it arrives rather than whole. A scan can be hundreds of megabytes, and the
 * triangles are going to the graphics card in pieces anyway; holding the file as well
 * would be a second copy of the largest thing on the page, kept only to be thrown away.
 * So push() takes the bytes in whatever pieces the stream hands over, and every triangle
 * goes out the moment its last byte is in.
 */

var VW_STL_HEADER = 84;
var VW_STL_RECORD = 50;

/*
 * How much of the start of a file is looked at before deciding which kind it is. Any
 * text STL says "solid" within its first few bytes, and any binary one has numbers in
 * its first few triangles, whose bytes are never all printable; this is far more than
 * either needs, and still a fraction of the first piece a stream hands over.
 */
var VW_STL_SNIFF = 512;

/*
 * A text file can hold no space for a long time only if it is not an STL. Past this, a
 * piece with no space in it is refused rather than held while more arrives.
 */
var VW_STL_LONGEST_WORD = 1024 * 1024;

var VW_STL_TEN = [1, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13,
  1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22];

var VW_STL_NOT_STL = "It does not begin the way an STL file does: as text starting with " +
  "the word solid, or as a binary header followed by a count of triangles.";

/*
 * Reads an STL as it arrives.
 *
 * total is the file's length in bytes, or -1 if nobody said. onStart({ kind, triangles })
 * is called once the kind is known, with the count a binary file's header gives, or -1 for
 * a text file, whose count nobody knows until it has all been read. onTriangle(ax, ay, az,
 * bx, by, bz, cx, cy, cz) takes each triangle's corners in the order the file lists them.
 * Either may throw to stop the reading, and the throw comes out of push().
 *
 * push(bytes) takes the next piece of the file, as a Uint8Array. end() says there are no
 * more, and answers { kind, triangles }, the number of triangles read.
 */
function vwStlReader(total, onStart, onTriangle) {
  var head = null;
  var feed = null;
  var finish = null;

  function decide() {
    var kind = vwStlKind(head, total);
    if (!kind) throw new Error(VW_STL_NOT_STL);
    var parts = kind === "binary"
      ? vwStlBinary(head, total, onTriangle)
      : vwStlText(onTriangle);
    feed = parts.push;
    finish = parts.end;
    onStart({ kind: kind, triangles: parts.declared });
    var held = head;
    head = null;
    feed(held);
  }

  return {
    push: function (bytes) {
      if (feed) {
        feed(bytes);
        return;
      }
      head = head ? vwStlJoin(head, bytes) : bytes;
      if (head.length >= VW_STL_SNIFF) decide();
    },
    end: function () {
      if (!feed) {
        if (!head || !head.length) throw new Error("The file is empty.");
        decide();
      }
      return finish();
    }
  };
}

/*
 * "binary", "ascii", or null for neither, from the first bytes of a file and its length.
 *
 * A binary file whose length is exactly what its count says is binary, whatever its header
 * holds. That comes first because the header is free text and a great many exporters,
 * SolidWorks among them, begin it with the word solid, which is how a text STL begins.
 * Failing that, the bytes decide: a text STL is printable throughout, and a binary one is
 * not, since the bytes of its count and its coordinates never all happen to be.
 *
 * A file that is text but does not begin with solid is not an STL of either kind, and is
 * refused rather than read as binary, which would draw its letters as triangles.
 */
function vwStlKind(head, total) {
  if (head.length >= VW_STL_HEADER && total >= 0 &&
      total === VW_STL_HEADER + VW_STL_RECORD * vwStlCount(head)) {
    return "binary";
  }
  if (vwStlIsText(head)) return vwStlSaysSolid(head) ? "ascii" : null;
  return head.length >= VW_STL_HEADER ? "binary" : null;
}

/* The triangle count in a binary file's header. */
function vwStlCount(head) {
  return (head[80] | (head[81] << 8) | (head[82] << 16)) + head[83] * 16777216;
}

/*
 * Whether the start of a file is printable. Bytes from 0x80 up pass, since a solid's name
 * can be in any language, and so do the whitespace controls, including the Ctrl-Z old DOS
 * programs ended text with.
 */
function vwStlIsText(head) {
  var n = Math.min(head.length, VW_STL_SNIFF);
  for (var i = 0; i < n; i++) {
    var b = head[i];
    if (b < 0x20 && !(b >= 0x09 && b <= 0x0D) && b !== 0x1A) return false;
    if (b === 0x7F) return false;
  }
  return true;
}

/* Whether a file begins with "solid", after any byte order mark and blank space. */
function vwStlSaysSolid(head) {
  var at = 0;
  if (head[0] === 0xEF && head[1] === 0xBB && head[2] === 0xBF) at = 3;
  while (at < head.length && head[at] <= 0x20) at++;
  return vwStlWord(head, at, "solid");
}

/* Whether bytes[at..] spell [word], in either case. [word] is lower case letters. */
function vwStlWord(bytes, at, word) {
  if (at + word.length > bytes.length) return false;
  for (var i = 0; i < word.length; i++) {
    if ((bytes[at + i] | 0x20) !== word.charCodeAt(i)) return false;
  }
  return true;
}

function vwStlJoin(a, b) {
  var joined = new Uint8Array(a.length + b.length);
  joined.set(a, 0);
  joined.set(b, a.length);
  return joined;
}

/*
 * A binary STL's triangles, fifty bytes at a time.
 *
 * How many is the one thing a binary file's header is trusted for, and only as far as the
 * file bears it out. A count of zero is what some exporters write whatever the file holds,
 * so it means read to the end. A count the file is too short for means it was cut off,
 * in a download say, and what did arrive is still drawn. Anything after the last counted
 * triangle is not read.
 */
function vwStlBinary(head, total, onTriangle) {
  var declared = vwStlCount(head);
  var want = declared;
  if (total >= 0) {
    var room = Math.max(0, Math.floor((total - VW_STL_HEADER) / VW_STL_RECORD));
    if (want === 0 || want > room) want = room;
  } else if (want === 0) {
    want = Infinity;
  }

  var skip = VW_STL_HEADER;
  var read = 0;
  // A triangle split between two pieces of the stream is put back together here
  var carry = new Uint8Array(VW_STL_RECORD);
  var carryView = new DataView(carry.buffer);
  var carried = 0;

  function record(v, o) {
    read++;
    onTriangle(
      v.getFloat32(o + 12, true), v.getFloat32(o + 16, true), v.getFloat32(o + 20, true),
      v.getFloat32(o + 24, true), v.getFloat32(o + 28, true), v.getFloat32(o + 32, true),
      v.getFloat32(o + 36, true), v.getFloat32(o + 40, true), v.getFloat32(o + 44, true)
    );
  }

  return {
    declared: want === Infinity ? -1 : want,
    push: function (bytes) {
      var off = 0;
      var len = bytes.length;
      if (skip > 0) {
        off = Math.min(skip, len);
        skip -= off;
      }
      if (off >= len || read >= want) return;
      if (carried > 0) {
        var take = Math.min(VW_STL_RECORD - carried, len - off);
        carry.set(bytes.subarray(off, off + take), carried);
        carried += take;
        off += take;
        if (carried < VW_STL_RECORD) return;
        carried = 0;
        record(carryView, 0);
      }
      var view = new DataView(bytes.buffer, bytes.byteOffset, len);
      while (off + VW_STL_RECORD <= len && read < want) {
        record(view, off);
        off += VW_STL_RECORD;
      }
      if (read < want && off < len) {
        carry.set(bytes.subarray(off, len), 0);
        carried = len - off;
      }
    },
    end: function () {
      return { kind: "binary", triangles: read };
    }
  };
}

/*
 * A text STL's triangles, a word at a time.
 *
 * Only three words matter. "vertex" is followed by a corner's three numbers, and every
 * third corner closes a triangle. "facet" and "loop", and their ends, start the count of
 * corners again, so a facet that lost a number, or has a corner too few, costs that facet
 * and nothing after it. Everything else, normals and names included, is passed over. The
 * words are matched in either case, since a few old exporters wrote them in capitals.
 *
 * A piece of the stream can end in the middle of a word, so everything after its last
 * space is kept and read at the front of the next.
 */
function vwStlText(onTriangle) {
  var carry = null;
  var need = 0;
  var corners = 0;
  var bad = false;
  var read = 0;
  var ax = 0, ay = 0, az = 0, bx = 0, by = 0, bz = 0, px = 0, py = 0, pz = 0;

  function corner() {
    if (corners === 0) {
      ax = px; ay = py; az = pz;
      corners = 1;
    } else if (corners === 1) {
      bx = px; by = py; bz = pz;
      corners = 2;
    } else {
      if (!bad) {
        read++;
        onTriangle(ax, ay, az, bx, by, bz, px, py, pz);
      }
      corners = 0;
      bad = false;
    }
  }

  function word(d, s, e) {
    if (need > 0) {
      var v = vwStlNumber(d, s, e);
      if (v === v) {
        if (need === 3) px = v;
        else if (need === 2) py = v;
        else pz = v;
        if (--need === 0) corner();
        return;
      }
      // A corner short of a number. It still counts as a corner, so the ones after it
      // keep their places, but the triangle it belongs to is not drawn.
      need = 0;
      bad = true;
      corner();
    }
    var len = e - s;
    if (len === 6 && vwStlWord(d, s, "vertex")) {
      need = 3;
    } else if ((len === 5 && vwStlWord(d, s, "facet")) ||
               (len === 4 && vwStlWord(d, s, "loop")) ||
               (len === 7 && vwStlWord(d, s, "endloop")) ||
               (len === 8 && vwStlWord(d, s, "endfacet"))) {
      corners = 0;
      bad = false;
    }
  }

  function scan(d, from, to) {
    var i = from;
    while (i < to) {
      if (d[i] <= 0x20) {
        i++;
        continue;
      }
      var s = i;
      while (i < to && d[i] > 0x20) i++;
      word(d, s, i);
    }
  }

  return {
    declared: -1,
    push: function (bytes) {
      var d = carry ? vwStlJoin(carry, bytes) : bytes;
      carry = null;
      var last = d.length - 1;
      while (last >= 0 && d[last] > 0x20) last--;
      if (last < 0) {
        if (d.length > VW_STL_LONGEST_WORD) throw new Error(VW_STL_NOT_STL);
        carry = d.slice(0);
        return;
      }
      scan(d, 0, last + 1);
      // A copy, so the piece it came from can go
      if (last + 1 < d.length) carry = d.slice(last + 1);
    },
    end: function () {
      if (carry) scan(carry, 0, carry.length);
      carry = null;
      return { kind: "ascii", triangles: read };
    }
  };
}

/*
 * The number spelled by bytes[s..e), or NaN if they spell anything else.
 *
 * Written out rather than handed to parseFloat, which would mean making a string of every
 * number first: a text STL is almost nothing but numbers, twelve to a triangle, and a
 * million triangles is not unusual. A power of ten up to 1e22 is exact as a double, so for
 * the numbers files actually hold, fifteen significant digits at most and an exponent
 * within twenty-two, this is exactly the double parseFloat gives. Past that it can be a
 * unit or so off in the last place, and digits past the seventeenth are only counted,
 * which is still far finer than the float32 every corner is stored as. nan, inf and
 * hexadecimal are not numbers here, and cost their triangle.
 */
function vwStlNumber(d, s, e) {
  var i = s;
  var neg = false;
  if (d[i] === 0x2D) {
    neg = true;
    i++;
  } else if (d[i] === 0x2B) {
    i++;
  }
  var mant = 0;
  var digits = 0;
  var exp = 0;
  var any = false;
  var c;
  while (i < e) {
    c = d[i] - 0x30;
    if (c < 0 || c > 9) break;
    any = true;
    if (digits < 17) {
      mant = mant * 10 + c;
      if (mant) digits++;
    } else {
      exp++;
    }
    i++;
  }
  if (i < e && d[i] === 0x2E) {
    i++;
    while (i < e) {
      c = d[i] - 0x30;
      if (c < 0 || c > 9) break;
      any = true;
      if (digits < 17) {
        mant = mant * 10 + c;
        if (mant) digits++;
        exp--;
      }
      i++;
    }
  }
  if (!any) return NaN;
  if (i < e && (d[i] | 0x20) === 0x65) {
    i++;
    var eneg = false;
    if (d[i] === 0x2D) {
      eneg = true;
      i++;
    } else if (d[i] === 0x2B) {
      i++;
    }
    var ev = 0;
    var eany = false;
    while (i < e) {
      c = d[i] - 0x30;
      if (c < 0 || c > 9) break;
      eany = true;
      if (ev < 100000) ev = ev * 10 + c;
      i++;
    }
    if (!eany) return NaN;
    exp += eneg ? -ev : ev;
  }
  if (i !== e) return NaN;
  var v;
  if (exp === 0 || mant === 0) v = mant;
  else if (exp > 0) v = exp <= 22 ? mant * VW_STL_TEN[exp] : mant * Math.pow(10, exp);
  else v = exp >= -22 ? mant / VW_STL_TEN[-exp] : mant / Math.pow(10, -exp);
  return neg ? -v : v;
}
