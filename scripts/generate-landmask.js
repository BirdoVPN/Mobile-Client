#!/usr/bin/env node
/**
 * Rasterise Natural Earth land GeoJSON → packed boolean grid → Kotlin source.
 *
 * Output: app/src/main/java/app/birdo/vpn/ui/components/WorldLandmask.kt
 *
 * Grid resolution: 720 cols × 360 rows = 0.5° per cell. That's 25× more
 * detail than the previous 144×72 hand-mask, enough to render every
 * recognisable country silhouette including Caribbean / Pacific islands.
 *
 * Storage: bits packed into a Base64 string of (720*360)/8 = 32400 bytes.
 * Kotlin side decodes once into a BooleanArray on first use.
 *
 * Usage:
 *   node scripts/generate-landmask.js path/to/ne_50m_land.json
 */

'use strict';

const fs = require('fs');
const path = require('path');

const COLS = 720;
const ROWS = 360;

const inputPath = process.argv[2];
if (!inputPath) {
  console.error('Usage: node generate-landmask.js <ne_50m_land.json>');
  process.exit(1);
}
const geo = JSON.parse(fs.readFileSync(inputPath, 'utf8'));

// Boolean grid: row 0 = 90°N, row ROWS-1 = 90°S; col 0 = 180°W, col COLS-1 = 180°E.
const grid = new Uint8Array(COLS * ROWS);

function lonToCol(lon) {
  return ((lon + 180) / 360) * COLS;
}
function latToRow(lat) {
  return ((90 - lat) / 180) * ROWS;
}

// Point-in-polygon (ring) using even-odd ray casting in pixel space.
function rasterisePolygon(rings) {
  // Each ring: array of [lon, lat] in degrees.
  // Find bbox in pixel coords.
  let minR = ROWS, maxR = -1;
  const pxRings = rings.map(ring => {
    const px = ring.map(([lon, lat]) => [lonToCol(lon), latToRow(lat)]);
    for (const [, y] of px) {
      const r = Math.floor(y);
      if (r < minR) minR = r;
      if (r > maxR) maxR = r;
    }
    return px;
  });
  if (maxR < 0) return;
  minR = Math.max(0, minR);
  maxR = Math.min(ROWS - 1, maxR);

  for (let r = minR; r <= maxR; r++) {
    const y = r + 0.5; // sample at row centre
    const xs = [];
    for (const ring of pxRings) {
      for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const [xi, yi] = ring[i];
        const [xj, yj] = ring[j];
        if ((yi > y) !== (yj > y)) {
          const x = xi + ((y - yi) * (xj - xi)) / (yj - yi);
          xs.push(x);
        }
      }
    }
    xs.sort((a, b) => a - b);
    for (let k = 0; k + 1 < xs.length; k += 2) {
      const x0 = Math.max(0, Math.ceil(xs[k] - 0.5));
      const x1 = Math.min(COLS - 1, Math.floor(xs[k + 1] - 0.5));
      for (let c = x0; c <= x1; c++) grid[r * COLS + c] = 1;
    }
  }
}

function processGeometry(geom) {
  if (!geom) return;
  if (geom.type === 'Polygon') {
    rasterisePolygon(geom.coordinates);
  } else if (geom.type === 'MultiPolygon') {
    for (const poly of geom.coordinates) rasterisePolygon(poly);
  } else if (geom.type === 'GeometryCollection') {
    for (const g of geom.geometries) processGeometry(g);
  }
}

if (geo.type === 'FeatureCollection') {
  for (const f of geo.features) processGeometry(f.geometry);
} else if (geo.type === 'Feature') {
  processGeometry(geo.geometry);
} else {
  processGeometry(geo);
}

// Pack bits LSB-first within each byte.
const byteLen = Math.ceil((COLS * ROWS) / 8);
const packed = Buffer.alloc(byteLen);
for (let i = 0; i < COLS * ROWS; i++) {
  if (grid[i]) packed[i >> 3] |= 1 << (i & 7);
}
const b64 = packed.toString('base64');

let landCount = 0;
for (let i = 0; i < COLS * ROWS; i++) if (grid[i]) landCount++;
console.error(`Grid: ${COLS}x${ROWS} = ${COLS * ROWS} cells, land=${landCount} (${((landCount / (COLS * ROWS)) * 100).toFixed(1)}%), packed=${byteLen} bytes, base64=${b64.length} chars`);

// Chunk the base64 into ~80-char lines for readable Kotlin source.
const lines = [];
for (let i = 0; i < b64.length; i += 76) lines.push(b64.slice(i, i + 76));
const kotlinB64 = lines.map(l => `        "${l}"`).join(" +\n");

const kt = `package app.birdo.vpn.ui.components

import android.util.Base64

/**
 * Real-world land/sea mask at 0.5° resolution (${COLS} cols × ${ROWS} rows = ${COLS * ROWS} cells).
 *
 * Generated from Natural Earth 50m physical land vectors (public domain) by
 * \`scripts/generate-landmask.js\`. Each cell is rasterised by ray-casting the
 * land polygons in equirectangular pixel space, giving accurate continent
 * outlines plus large islands (UK, Japan, Madagascar, Iceland, Cuba, NZ, etc.)
 * and most named smaller islands at this density.
 *
 * Storage: ${COLS}×${ROWS} bits → ${byteLen} bytes → ${b64.length}-char Base64
 * literal, decoded once into a BooleanArray on first access.
 */
internal object WorldLandmask {
    private const val COLS = ${COLS}
    private const val ROWS = ${ROWS}

    private val packedB64: String =
${kotlinB64}

    private val cells: BooleanArray by lazy {
        val bytes = Base64.decode(packedB64, Base64.DEFAULT)
        val out = BooleanArray(COLS * ROWS)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            var bit = 0
            while (bit < 8 && i < out.size) {
                out[i] = (v ushr bit) and 1 == 1
                bit++; i++
            }
        }
        out
    }

    fun rowCount(): Int = ROWS
    fun colCount(): Int = COLS

    /** Returns true if mask cell (r, c) is land. */
    fun isLandCell(r: Int, c: Int): Boolean {
        if (r !in 0 until ROWS) return false
        val cc = ((c % COLS) + COLS) % COLS
        return cells[r * COLS + cc]
    }

    /** Returns true if the given (lat, lon) in degrees is on land. */
    fun isLand(latDeg: Double, lonDeg: Double): Boolean {
        val r = (((90.0 - latDeg) / 180.0) * ROWS).toInt().coerceIn(0, ROWS - 1)
        val c = (((lonDeg + 180.0) / 360.0) * COLS).toInt().coerceIn(0, COLS - 1)
        return cells[r * COLS + c]
    }
}
`;

const outPath = path.resolve(__dirname, '..', 'app', 'src', 'main', 'java', 'app', 'birdo', 'vpn', 'ui', 'components', 'WorldLandmask.kt');
fs.writeFileSync(outPath, kt, 'utf8');
console.error(`Wrote ${outPath} (${kt.length} chars)`);
