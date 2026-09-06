(() => {
    const splash = document.getElementById("treasury-loading");
    const status = document.getElementById("launch-status");
    const reload = document.getElementById("launch-reload");
    reload.addEventListener("click", () => window.location.reload());
    const observer = new MutationObserver(() => {
        if (document.querySelector("canvas")) {
            splash.remove();
            observer.disconnect();
        }
    });
    observer.observe(document.body, { childList: true, subtree: true });
    const showFailure = () => {
        if (!splash.isConnected) return;
        status.textContent = "Treasury could not start. Check your connection and use an up-to-date browser, then try again.";
        reload.hidden = false;
    };
    window.addEventListener("error", showFailure, true);
    window.addEventListener("unhandledrejection", showFailure);
})();
