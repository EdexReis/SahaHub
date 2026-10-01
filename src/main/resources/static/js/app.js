// SahaHub: küçük yardımcı davranışlar. Sayfa JS olmadan da çalışır; bunlar yalnızca kolaylıktır.
(function () {
	"use strict";

	// Geçici tutma geri sayımı. Süre dolunca onay butonu pasifleşir.
	// Asıl kontrol sunucudadır: süre dolmuş tutma, buton çalışsa bile onaylanmaz.
	function startCountdowns(root) {
		root.querySelectorAll("[data-expires]").forEach(function (el) {
			if (el.dataset.started) return;
			el.dataset.started = "1";
			var expires = Date.parse(el.dataset.expires);
			var out = el.querySelector("[data-countdown]");
			var target = document.querySelector(el.dataset.disables || "#none");
			function tick() {
				var left = Math.max(0, Math.round((expires - Date.now()) / 1000));
				var m = Math.floor(left / 60), s = left % 60;
				if (out) out.textContent = m + ":" + (s < 10 ? "0" : "") + s + " dakika daha";
				if (left === 0) {
					el.querySelector("[data-expired-text]") && (el.querySelector("[data-expired-text]").hidden = false);
					if (target) { target.disabled = true; target.setAttribute("aria-disabled", "true"); }
					return;
				}
				setTimeout(tick, 1000);
			}
			tick();
		});
	}

	// Tehlikeli işlemlerde onay sor: <form data-confirm="...">
	document.addEventListener("submit", function (e) {
		var msg = e.target.getAttribute && e.target.getAttribute("data-confirm");
		if (msg && !window.confirm(msg)) e.preventDefault();
	}, true);

	// Dosya boyutu ön kontrolü: <input type="file" data-max-bytes="..."> (asıl sınır sunucuda)
	document.addEventListener("change", function (e) {
		var el = e.target;
		if (!el.matches || !el.matches("input[type=file][data-max-bytes]") || !el.files || !el.files[0]) return;
		var max = parseInt(el.dataset.maxBytes, 10);
		el.setCustomValidity(el.files[0].size > max ? "Dosya en fazla " + Math.round(max / 1048576) + " MB olabilir." : "");
		el.reportValidity();
	});

	// Yazdır düğmesi (satır içi script CSP ile yasak olduğu için buradan bağlanır)
	document.addEventListener("click", function (e) {
		if (e.target.matches && e.target.matches("[data-print]")) window.print();
	});

	// Değişince formu gönder (takvim tarih seçici): <input data-autosubmit>
	document.addEventListener("change", function (e) {
		if (e.target.matches && e.target.matches("[data-autosubmit]")) e.target.form.submit();
	});

	// Seçili günü şeritte görünür yap; takvimde şimdiki saate kaydır
	function scrollIntoPlace(root) {
		var day = root.querySelector('.day[aria-current="date"]');
		if (day && day.scrollIntoView) day.scrollIntoView({ block: "nearest", inline: "center" });
		var scroller = root.querySelector(".cal-scroll[data-scroll-row]");
		if (scroller && !scroller.dataset.scrolled) {
			scroller.dataset.scrolled = "1";
			var hour = parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--hour")) || 48;
			var row = parseInt(scroller.dataset.scrollRow, 10);
			scroller.scrollTop = Math.max(0, (row - 5) * hour / 4);
		}
	}

	function init(root) {
		startCountdowns(root);
		scrollIntoPlace(root);
	}

	document.addEventListener("DOMContentLoaded", function () { init(document); });
	document.addEventListener("htmx:afterSettle", function (e) { init(e.target); });
	// Panel açıldığında odağı panel başlığına taşı (klavye kullanıcıları için)
	document.addEventListener("htmx:afterSwap", function (e) {
		if (e.target.id === "panel") {
			e.target.classList.add("is-sheet");
			var h = e.target.querySelector("h2");
			if (h) { h.setAttribute("tabindex", "-1"); h.focus(); }
		}
	});
})();
