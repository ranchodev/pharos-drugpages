// Miscelaneous code to support visualization

(function (H) {
    H.wrap(H.Renderer.prototype, 'label', function (proceed, str, x, y, shape, anchorX, anchorY, useHTML) {
        if (/class="fa/.test(str))    useHTML = true;
        // Run original proceed method
        return proceed.apply(this, [].slice.call(arguments, 1));
    });
}(Highcharts));