// Jukebox web login compatibility fix v129ef
// Corrige ENTRAR e CRIAR NOVO USUÁRIO para mouse, toque, Enter e controles de TV Box.
(function () {
  "use strict";

  function valueOf(id) {
    var el = document.getElementById(id);
    return el ? String(el.value || "").trim() : "";
  }

  function syncLoginFields() {
    var user = valueOf("loginUserMobile");
    var pass = valueOf("loginPasswordMobile");
    var userDisplay = document.getElementById("loginUserDisplay");
    var passDisplay = document.getElementById("loginPasswordDisplay");
    if (userDisplay && user) userDisplay.textContent = user;
    if (passDisplay && pass) passDisplay.textContent = "•".repeat(pass.length);
    return { user: user, pass: pass };
  }

  function submitLogin(ev) {
    var btn = document.getElementById("loginBtn");
    if (!btn) return;
    var target = ev && ev.target;
    if (ev && ev.type === "keydown") {
      var k = ev.key || "";
      var code = ev.keyCode || ev.which || 0;
      if (!(k === "Enter" || code === 13 || code === 23 || code === 66)) return;
    } else if (target !== btn && !(target && target.closest && target.closest("#loginBtn"))) {
      return;
    }
    syncLoginFields();
    // Dispara o fluxo original do app.js sem depender do teclado virtual.
    if (typeof window.login === "function") {
      ev && ev.preventDefault && ev.preventDefault();
      window.login();
    }
  }

  function openRegister(ev) {
    var btn = document.getElementById("openRegisterBtn");
    if (!btn) return;
    var target = ev && ev.target;
    if (ev && ev.type === "keydown") {
      var k = ev.key || "";
      var code = ev.keyCode || ev.which || 0;
      if (!(k === "Enter" || k === " " || code === 13 || code === 23 || code === 66)) return;
      if (document.activeElement !== btn) return;
    } else if (target !== btn && !(target && target.closest && target.closest("#openRegisterBtn"))) {
      return;
    }
    ev && ev.preventDefault && ev.preventDefault();
    if (typeof window.openRegisterModal === "function") {
      window.openRegisterModal();
      return;
    }
    var modal = document.getElementById("registerModal");
    if (modal) {
      modal.classList.remove("hidden");
      modal.setAttribute("aria-hidden", "false");
      modal.style.zIndex = "10050";
      var first = modal.querySelector("input,button,select,textarea");
      if (first) setTimeout(function () { first.focus(); }, 50);
    }
  }

  document.addEventListener("input", function (ev) {
    if (ev.target && (ev.target.id === "loginUserMobile" || ev.target.id === "loginPasswordMobile")) syncLoginFields();
  }, true);
  document.addEventListener("click", submitLogin, true);
  document.addEventListener("keydown", submitLogin, true);
  document.addEventListener("click", openRegister, true);
  document.addEventListener("keydown", openRegister, true);
})();