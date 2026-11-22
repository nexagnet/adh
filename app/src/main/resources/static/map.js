/*
 * Bản đồ khách theo tỉnh (Leaflet + OpenStreetMap) và lớp thời tiết chạy theo giờ.
 * Không có mã nội tuyến: trang chặn script/kiểu dáng nội tuyến (CSP), nên màu chỉ đặt qua lớp CSS hoặc thuộc tính SVG.
 * Dữ liệu: /map/vn-provinces.geojson (ranh giới tỉnh, tệp tĩnh) và /map/data.json (số khách + thời tiết từng giờ).
 */
(function () {
  'use strict';

  var el = document.getElementById('vnmap');
  if (!el) {
    return;
  }
  var status = document.getElementById('wx-status');
  function say(text) {
    if (status) {
      status.textContent = text || '';
    }
  }
  if (typeof L === 'undefined') {
    say('Không tải được thư viện bản đồ. Bảng "Số khách theo tỉnh" bên dưới vẫn dùng được.');
    return;
  }

  // Màu tô tỉnh theo số khách (khớp các ô màu ở chú giải .swatch.lv0 ... lv4).
  var LEVEL_FILL = ['#e4e9ef', '#cfe0f5', '#8db6e6', '#3f7fc9', '#17488c'];
  var LEVEL_OPACITY = [0.3, 0.6, 0.65, 0.7, 0.75];
  var SEL_COLOR = '#c0392b';

  // Mốc nhiệt độ lệch nửa độ để khớp số đã làm tròn hiện trên bản đồ (12,4°C hiện là 12 nên thuộc nhóm "≤ 12").
  var TEMP_STEPS = [[12.5, 't0', '≤ 12'], [16.5, 't1', '13–16'], [20.5, 't2', '17–20'], [24.5, 't3', '21–24'],
    [28.5, 't4', '25–28'], [32.5, 't5', '29–32'], [36.5, 't6', '33–36'], [Infinity, 't7', '> 36']];
  var RAIN_STEPS = [[0.05, 'r0', '0'], [0.5, 'r1', '< 0,5'], [2, 'r2', '0,5–2'], [5, 'r3', '2–5'], [10, 'r4', '5–10'],
    [Infinity, 'r5', '> 10']];
  var CLOUD_STEPS = [[20, 'c0', '< 20'], [40, 'c1', '20–40'], [60, 'c2', '40–60'], [80, 'c3', '60–80'], [Infinity, 'c4', '≥ 80']];
  var MODES = {
    temp: {steps: TEMP_STEPS, title: 'Nhiệt độ (°C)'},
    rain: {steps: RAIN_STEPS, title: 'Lượng mưa (mm mỗi giờ)'},
    cloud: {steps: CLOUD_STEPS, title: 'Mây phủ (%)'}
  };

  function stepOf(steps, value) {
    for (var i = 0; i < steps.length; i++) {
      if (value < steps[i][0]) {
        return steps[i];
      }
    }
    return steps[steps.length - 1];
  }

  function vnNumber(n, digits) {
    return n.toFixed(digits).replace('.', ',');
  }

  var clockFormat = new Intl.DateTimeFormat('vi-VN', {
    weekday: 'short', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
    hour12: false, timeZone: 'Asia/Ho_Chi_Minh'
  });

  // ---- Bản đồ nền ----
  var map = L.map(el, {minZoom: 5, maxZoom: 12, zoomSnap: 0.25, worldCopyJump: false,
    maxBounds: [[1, 94], [29, 123]], maxBoundsViscosity: 0.8});
  map.attributionControl.setPrefix('<a href="https://leafletjs.com" target="_blank" rel="noopener noreferrer">Leaflet</a>');
  // Chính sách ô bản đồ OpenStreetMap: ghi nguồn, gửi Referer (trang này mặc định không gửi nên đặt riêng cho ô bản đồ),
  // không tải hàng loạt. Giới hạn zoom tối đa 12 để giảm lượng ô tải về.
  L.tileLayer(el.getAttribute('data-tiles'), {
    maxZoom: 12, referrerPolicy: 'origin', attribution: el.getAttribute('data-attribution') || ''
  }).addTo(map);
  map.attributionControl.addAttribution('Ranh giới: <a href="https://www.naturalearthdata.com/" target="_blank" '
      + 'rel="noopener noreferrer">Natural Earth</a>');
  map.fitBounds([[7.5, 102.0], [23.8, 116.0]], {padding: [4, 4]});

  // Hai quần đảo của Việt Nam nằm ngoài ranh giới đất liền: ghi nhãn để bản đồ đầy đủ.
  [['Quần đảo Hoàng Sa', 16.5, 112.0, ''], ['Quần đảo Trường Sa', 9.9, 114.3, ' isle-left']].forEach(function (isle) {
    L.marker([isle[1], isle[2]], {
      interactive: false, keyboard: false,
      icon: L.divIcon({className: 'isle' + isle[3], html: '<i class="isle-dot"></i><span class="isle-name">' + isle[0] + '</span>',
        iconSize: [150, 16], iconAnchor: isle[3] ? [146, 8] : [4, 8]})
    }).addTo(map);
  });

  function onZoom() {
    el.classList.toggle('wx-compact', map.getZoom() < 6.75);
  }
  map.on('zoomend', onZoom);
  onZoom();

  // ---- Dữ liệu ----
  var selected = el.getAttribute('data-selected') || '';
  var layers = {};       // mã tỉnh -> lớp đa giác
  var info = {};         // mã tỉnh -> số khách, tên...
  var weather = null;    // {labels, series}
  var frames = [];       // các mốc giờ (giây)
  var rows = {};         // mã tỉnh -> {giây: dòng số liệu}
  var frameIndex = 0;
  var mode = 'off';
  var chips = {};
  var chipLayer = L.layerGroup();
  var timer = null;

  function styleFor(code, hover) {
    var d = info[code];
    var level = d ? d.level : 0;
    var isSel = code === selected;
    return {
      color: isSel ? SEL_COLOR : '#56606e', weight: isSel ? 3 : (hover ? 2.5 : 1),
      fillColor: LEVEL_FILL[level], fillOpacity: LEVEL_OPACITY[level] + (hover ? 0.1 : 0)
    };
  }

  function restyleAll() {
    Object.keys(layers).forEach(function (code) {
      layers[code].setStyle(styleFor(code, false));
    });
    if (layers[selected]) {
      layers[selected].bringToFront();
    }
  }

  function rowAt(code, epoch) {
    var byTime = rows[code];
    if (!byTime) {
      return null;
    }
    if (byTime[epoch]) {
      return byTime[epoch];
    }
    var best = null;
    var bestGap = 5400; // chỉ chấp nhận lệch tối đa 1 giờ 30 phút
    Object.keys(byTime).forEach(function (t) {
      var gap = Math.abs(Number(t) - epoch);
      if (gap <= bestGap) {
        best = byTime[t];
        bestGap = gap;
      }
    });
    return best;
  }

  function currentRow(code) {
    return frames.length ? rowAt(code, frames[frameIndex]) : null;
  }

  function tooltipNode(code) {
    var d = info[code];
    var box = document.createElement('div');
    var name = document.createElement('strong');
    name.textContent = d ? d.name : code;
    box.appendChild(name);
    var line = document.createElement('div');
    line.textContent = (d ? d.count : 0) + ' khách';
    box.appendChild(line);
    var row = mode === 'off' ? null : currentRow(code);
    if (row && weather) {
      var label = weather.labels[row[4]];
      var w = document.createElement('div');
      w.textContent = (label && label.icon ? label.icon + ' ' : '') + (label ? label.text + ', ' : '')
          + Math.round(row[1]) + '°C, mưa ' + vnNumber(row[2], 1) + ' mm/giờ, mây ' + Math.round(row[3]) + '%';
      box.appendChild(w);
    }
    return box;
  }

  function pick(code, fly) {
    if (!layers[code]) {
      return;
    }
    selected = code;
    restyleAll();
    if (fly) {
      if (el.scrollIntoView) {
        el.scrollIntoView({behavior: 'smooth', block: 'center'});
      }
      map.flyToBounds(layers[code].getBounds(), {maxZoom: 8, padding: [20, 20], duration: 0.6});
    }
    var url = window.location.pathname + '?province=' + encodeURIComponent(code);
    fetch(url, {credentials: 'same-origin', headers: {'Accept': 'text/html'}}).then(function (r) {
      if (!r.ok) {
        throw new Error('lỗi ' + r.status);
      }
      return r.text();
    }).then(function (html) {
      var doc = new DOMParser().parseFromString(html, 'text/html');
      var fresh = doc.getElementById('detail');
      var holder = document.getElementById('detail');
      if (!fresh || !holder) {
        throw new Error('thiếu phần chi tiết');
      }
      holder.replaceChildren.apply(holder, Array.prototype.map.call(fresh.childNodes, function (n) {
        return document.importNode(n, true);
      }));
      window.history.replaceState(null, '', url);
    }).catch(function () {
      window.location.href = url; // dự phòng: tải lại cả trang
    });
  }

  // ---- Lớp thời tiết ----
  var bar = document.getElementById('wx-bar');
  var timeBox = document.getElementById('wx-time');
  var slider = document.getElementById('wx-slider');
  var clock = document.getElementById('wx-clock');
  var playBtn = document.getElementById('wx-play');
  var legend = document.getElementById('wx-legend');
  var modeButtons = bar ? Array.prototype.slice.call(bar.querySelectorAll('.wx-mode')) : [];

  function chipFor(code, d) {
    var marker = L.marker([d.lat, d.lon], {
      interactive: false, keyboard: false,
      icon: L.divIcon({className: 'wx-chip', html: '<span class="wx-v"></span>', iconSize: [40, 22], iconAnchor: [20, 11]})
    });
    chips[code] = marker;
    marker.addTo(chipLayer);
  }

  function paintChips() {
    if (mode === 'off' || !frames.length) {
      return;
    }
    var spec = MODES[mode];
    Object.keys(chips).forEach(function (code) {
      var node = chips[code].getElement();
      if (!node) {
        return;
      }
      var span = node.firstChild;
      var row = currentRow(code);
      if (!row) {
        span.className = 'wx-v wx-none';
        span.textContent = '–';
        return;
      }
      var value = mode === 'temp' ? row[1] : (mode === 'rain' ? row[2] : row[3]);
      var step = stepOf(spec.steps, value);
      span.className = 'wx-v ' + step[1];
      span.textContent = mode === 'temp' ? Math.round(value) + '°'
          : (mode === 'rain' ? (value < 0.05 ? '0' : vnNumber(value, 1)) : Math.round(value) + '%');
    });
  }

  function paintClock() {
    if (!frames.length) {
      return;
    }
    clock.textContent = clockFormat.format(new Date(frames[frameIndex] * 1000)) + ' (giờ Việt Nam)';
    slider.value = String(frameIndex);
    slider.setAttribute('aria-valuetext', clock.textContent);
  }

  function paintLegend() {
    legend.replaceChildren();
    if (mode === 'off') {
      legend.hidden = true;
      return;
    }
    var spec = MODES[mode];
    var title = document.createElement('span');
    title.className = 'wx-legend-title';
    title.textContent = spec.title + ':';
    legend.appendChild(title);
    spec.steps.forEach(function (step) {
      var key = document.createElement('span');
      key.className = 'wx-key ' + step[1];
      key.textContent = step[2];
      legend.appendChild(key);
    });
    legend.hidden = false;
  }

  function stop() {
    if (timer) {
      window.clearInterval(timer);
      timer = null;
    }
    if (playBtn) {
      playBtn.textContent = '▶ Chạy';
      playBtn.setAttribute('aria-label', 'Chạy thời tiết theo giờ');
    }
  }

  function play() {
    if (timer || !frames.length) {
      return;
    }
    playBtn.textContent = '⏸ Dừng';
    playBtn.setAttribute('aria-label', 'Dừng chạy thời tiết');
    timer = window.setInterval(function () {
      frameIndex = (frameIndex + 1) % frames.length;
      paintClock();
      paintChips();
    }, 700);
  }

  function setMode(next) {
    if (next !== 'off' && !frames.length) {
      return;
    }
    mode = next;
    modeButtons.forEach(function (b) {
      b.setAttribute('aria-pressed', String(b.getAttribute('data-mode') === mode));
    });
    if (mode === 'off') {
      stop();
      chipLayer.remove();
      timeBox.hidden = true;
    } else {
      chipLayer.addTo(map);
      timeBox.hidden = false;
      paintClock();
      paintChips();
    }
    paintLegend();
  }

  function setupWeather(w) {
    weather = w;
    Object.keys(w.series).forEach(function (code) {
      var byTime = {};
      w.series[code].forEach(function (row) {
        byTime[row[0]] = row;
      });
      rows[code] = byTime;
    });
    var nowSec = Date.now() / 1000;
    var set = {};
    Object.keys(rows).forEach(function (code) {
      Object.keys(rows[code]).forEach(function (t) {
        if (Number(t) >= nowSec - 3600) {
          set[t] = true;
        }
      });
    });
    frames = Object.keys(set).map(Number).sort(function (a, b) { return a - b; });
    if (!frames.length) {
      return false;
    }
    var best = 0;
    frames.forEach(function (t, i) {
      if (Math.abs(t - nowSec) < Math.abs(frames[best] - nowSec)) {
        best = i;
      }
    });
    frameIndex = best;
    slider.max = String(frames.length - 1);
    Object.keys(info).forEach(function (code) {
      if (rows[code]) {
        chipFor(code, info[code]);
      }
    });
    return true;
  }

  modeButtons.forEach(function (b) {
    b.addEventListener('click', function () { setMode(b.getAttribute('data-mode')); });
  });
  if (slider) {
    slider.addEventListener('input', function () {
      frameIndex = Number(slider.value);
      paintClock();
      paintChips();
    });
  }
  if (playBtn) {
    playBtn.addEventListener('click', function () {
      if (timer) {
        stop();
      } else {
        play();
      }
    });
  }
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      stop();
    }
  });

  // Bấm tên tỉnh ở bảng bên dưới: chọn ngay trên bản đồ, không tải lại trang.
  document.addEventListener('click', function (event) {
    var link = event.target.closest ? event.target.closest('a.map-pick') : null;
    if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.button !== 0) {
      return;
    }
    var code = link.getAttribute('data-code');
    if (code && layers[code]) {
      event.preventDefault();
      pick(code, true);
    }
  });

  modeButtons.forEach(function (b) {
    if (b.getAttribute('data-mode') !== 'off') {
      b.disabled = true;
    }
  });

  Promise.all([
    fetch(el.getAttribute('data-geojson'), {credentials: 'same-origin'}).then(function (r) { return r.json(); }),
    fetch(el.getAttribute('data-data'), {credentials: 'same-origin'}).then(function (r) { return r.json(); })
  ]).then(function (result) {
    var geo = result[0];
    var data = result[1];
    data.provinces.forEach(function (p) {
      info[p.code] = p;
    });
    L.geoJSON(geo, {
      style: function (feature) { return styleFor(feature.properties.code, false); },
      onEachFeature: function (feature, layer) {
        var code = feature.properties.code;
        layers[code] = layer;
        layer.bindTooltip(function () { return tooltipNode(code); }, {sticky: true, direction: 'auto', className: 'map-tip'});
        layer.on('mouseover', function () {
          layer.setStyle(styleFor(code, true));
        });
        layer.on('mouseout', function () {
          layer.setStyle(styleFor(code, false));
        });
        layer.on('click', function () { pick(code, false); });
      }
    }).addTo(map);
    restyleAll();

    var w = data.weather;
    if (!w || !w.enabled) {
      say('Thời tiết đang tắt nên chưa có lớp thời tiết trên bản đồ. Cách bật nằm ở khung "Thời tiết" bên cạnh.');
    } else if (!setupWeather(w)) {
      say('Chưa có dữ liệu thời tiết theo giờ. Bấm "Cập nhật thời tiết ngay" ở bên cạnh, đợi chừng một phút rồi tải lại trang.');
    } else {
      modeButtons.forEach(function (b) { b.disabled = false; });
      say('Thời tiết cập nhật lúc ' + new Date(w.updatedAt).toLocaleString('vi-VN', {timeZone: 'Asia/Ho_Chi_Minh'})
          + '. Chọn "Nhiệt độ", "Mưa" hoặc "Mây" rồi bấm "Chạy" để xem 48 giờ tới.');
    }
  }).catch(function () {
    say('Không tải được dữ liệu bản đồ. Hãy tải lại trang. Bảng "Số khách theo tỉnh" bên dưới vẫn dùng được.');
  });
}());
