// Tạo app/src/main/resources/static/map/vn-provinces.geojson (ranh giới 63 tỉnh/thành, đã làm gọn cho nhẹ trang web).
//
// Nguồn: Natural Earth, bảng "Admin 1 – States, Provinces" 1:10m, DOMAIN CÔNG CỘNG (public domain).
//   https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-admin-1-states-provinces/
// Cách chạy (Node 18 trở lên):
//   curl -L -o ne.geojson https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_1_states_provinces.geojson
//   node tools/build-vn-geojson.mjs ne.geojson app/src/main/resources/static/map/vn-provinces.geojson
//
// Tên tỉnh trong Natural Earth có chỗ sai chính tả nên ghép theo mã ISO 3166-2, không ghép theo tên.
import { readFileSync, writeFileSync } from 'node:fs';

const ISO_TO_CODE = {
  'VN-01': 'lai-chau', 'VN-02': 'lao-cai', 'VN-03': 'ha-giang', 'VN-04': 'cao-bang', 'VN-05': 'son-la',
  'VN-06': 'yen-bai', 'VN-07': 'tuyen-quang', 'VN-09': 'lang-son', 'VN-13': 'quang-ninh', 'VN-14': 'hoa-binh',
  'VN-18': 'ninh-binh', 'VN-20': 'thai-binh', 'VN-21': 'thanh-hoa', 'VN-22': 'nghe-an', 'VN-23': 'ha-tinh',
  'VN-24': 'quang-binh', 'VN-25': 'quang-tri', 'VN-26': 'hue', 'VN-27': 'quang-nam', 'VN-28': 'kon-tum',
  'VN-29': 'quang-ngai', 'VN-30': 'gia-lai', 'VN-31': 'binh-dinh', 'VN-32': 'phu-yen', 'VN-33': 'dak-lak',
  'VN-34': 'khanh-hoa', 'VN-35': 'lam-dong', 'VN-36': 'ninh-thuan', 'VN-37': 'tay-ninh', 'VN-39': 'dong-nai',
  'VN-40': 'binh-thuan', 'VN-41': 'long-an', 'VN-43': 'ba-ria-vung-tau', 'VN-44': 'an-giang', 'VN-45': 'dong-thap',
  'VN-46': 'tien-giang', 'VN-47': 'kien-giang', 'VN-49': 'vinh-long', 'VN-50': 'ben-tre', 'VN-51': 'tra-vinh',
  'VN-52': 'soc-trang', 'VN-53': 'bac-kan', 'VN-54': 'bac-giang', 'VN-55': 'bac-lieu', 'VN-56': 'bac-ninh',
  'VN-57': 'binh-duong', 'VN-58': 'binh-phuoc', 'VN-59': 'ca-mau', 'VN-61': 'hai-duong', 'VN-63': 'ha-nam',
  'VN-66': 'hung-yen', 'VN-67': 'nam-dinh', 'VN-68': 'phu-tho', 'VN-69': 'thai-nguyen', 'VN-70': 'vinh-phuc',
  'VN-71': 'dien-bien', 'VN-72': 'dak-nong', 'VN-73': 'hau-giang',
  'VN-CT': 'can-tho', 'VN-DN': 'da-nang', 'VN-HN': 'ha-noi', 'VN-HP': 'hai-phong', 'VN-SG': 'tphcm',
};
const TOLERANCE = 0.004;   // độ, khoảng 400 m: đủ mịn ở mức xem cả nước
const MIN_RING_AREA = 2e-4; // bỏ đảo nhỏ hơn chừng 2 km2

const [, , input, output] = process.argv;
if (!input || !output) {
  console.error('Cách dùng: node tools/build-vn-geojson.mjs ne.geojson vn-provinces.geojson');
  process.exit(1);
}

function perpDist(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1];
  if (dx === 0 && dy === 0) return Math.hypot(p[0] - a[0], p[1] - a[1]);
  const t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy);
  const tt = Math.max(0, Math.min(1, t));
  return Math.hypot(p[0] - (a[0] + tt * dx), p[1] - (a[1] + tt * dy));
}

function douglasPeucker(points, tol) {
  const keep = new Array(points.length).fill(false);
  keep[0] = keep[points.length - 1] = true;
  const stack = [[0, points.length - 1]];
  while (stack.length) {
    const [s, e] = stack.pop();
    let max = 0, idx = -1;
    for (let i = s + 1; i < e; i++) {
      const d = perpDist(points[i], points[s], points[e]);
      if (d > max) { max = d; idx = i; }
    }
    if (max > tol && idx > 0) {
      keep[idx] = true;
      stack.push([s, idx], [idx, e]);
    }
  }
  return points.filter((_, i) => keep[i]);
}

function area(ring) {
  let a = 0;
  for (let i = 0; i < ring.length - 1; i++) a += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1];
  return Math.abs(a / 2);
}

function simplifyRing(ring) {
  // Chia vòng làm hai nửa để Douglas-Peucker không co vòng kín thành một điểm.
  const mid = Math.floor(ring.length / 2);
  const a = douglasPeucker(ring.slice(0, mid + 1), TOLERANCE);
  const b = douglasPeucker(ring.slice(mid), TOLERANCE);
  const out = [...a, ...b.slice(1)].map(([x, y]) => [Math.round(x * 1000) / 1000, Math.round(y * 1000) / 1000]);
  return out.length >= 4 ? out : null;
}

const source = JSON.parse(readFileSync(input, 'utf8'));
const seen = new Set();
const features = [];
for (const f of source.features) {
  if (f.properties.adm0_a3 !== 'VNM') continue;
  const code = ISO_TO_CODE[f.properties.iso_3166_2];
  if (!code) throw new Error('Mã ISO chưa biết: ' + f.properties.iso_3166_2);
  if (seen.has(code)) throw new Error('Trùng tỉnh: ' + code);
  seen.add(code);
  const polygons = f.geometry.type === 'Polygon' ? [f.geometry.coordinates] : f.geometry.coordinates;
  const out = [];
  polygons.forEach((poly, i) => {
    if (i > 0 && area(poly[0]) < MIN_RING_AREA) return; // luôn giữ phần lớn nhất (đầu tiên)
    const rings = poly.map(simplifyRing).filter(Boolean);
    if (rings.length) out.push(rings);
  });
  features.push({ type: 'Feature', properties: { code }, geometry: { type: 'MultiPolygon', coordinates: out } });
}
if (features.length !== 63) throw new Error('Cần đủ 63 tỉnh, hiện có ' + features.length);
features.sort((a, b) => a.properties.code.localeCompare(b.properties.code));
writeFileSync(output, JSON.stringify({ type: 'FeatureCollection', features }));
console.log('Đã ghi', features.length, 'tỉnh vào', output);
