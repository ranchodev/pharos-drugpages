// Miscelaneous code to support visualization

(function (H) {
    H.wrap(H.Renderer.prototype, 'label', function (proceed, str, x, y, shape, anchorX, anchorY, useHTML) {
        if (/class="fa/.test(str))    useHTML = true;
        // Run original proceed method
        return proceed.apply(this, [].slice.call(arguments, 1));
    });
}(Highcharts));

var defaultExportConfig = {
    navigation: {
        buttonOptions: {
            theme: {
                style: {
                    useHTML: true
                },
                useHTML: true
            }
        }
    },
    exporting: {
        allowHTML: true,
        buttons: {
            contextButton: {
                enabled: false
            },
            exportButton: {
                text: '<i class="fa fa-download" aria-hidden="true" style="font-size: 1.5em;" title="">&nbsp;</i>',
                menuItems: Highcharts.getOptions().exporting.buttons.contextButton.menuItems
            }
        }
    }
};