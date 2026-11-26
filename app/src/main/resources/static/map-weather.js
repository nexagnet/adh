(() => {
  const status = document.querySelector('[data-weather-status]');
  if (!status) return;

  async function checkStatus() {
    try {
      const response = await fetch(status.dataset.weatherStatus, { cache: 'no-store', headers: { Accept: 'application/json' } });
      if (response.redirected || (response.ok && (await response.json()).state !== 'RUNNING')) {
        window.location.reload();
        return;
      }
    } catch {
      // Giữ nguyên bản đồ nếu kết nối tạm gián đoạn.
    }
    window.setTimeout(checkStatus, 3000);
  }

  window.setTimeout(checkStatus, 3000);
})();
