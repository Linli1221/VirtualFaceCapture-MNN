(() => {
  const bg = (selector) => {
    const element = selector === "html" ? document.documentElement : document.querySelector(selector);
    return element ? getComputedStyle(element).backgroundColor : "MISSING";
  };
  const canvas = document.getElementById("live2d");
  const context = canvas.getContext("webgl2") || canvas.getContext("webgl");
  let cornerAlpha = null;
  if (context && context.readPixels) {
    const pixel = new Uint8Array(4);
    context.readPixels(0, 0, 1, 1, context.RGBA, context.UNSIGNED_BYTE, pixel);
    cornerAlpha = pixel[3];
  }
  return {
    html: bg("html"),
    body: bg("body"),
    stage: bg("#stage"),
    ui: bg("#ui"),
    htmlClass: document.documentElement.className,
    bodyClass: document.body.className,
    cornerAlpha,
    pixelStats: __vfc.pixelStats(),
  };
})()
