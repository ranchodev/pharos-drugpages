function highlightTargetTable(elem) {
    var acc = $(elem).attr("id").split("-")[1];
    $("#row-" + acc).addClass("active");
}
function unhighlightTargetTable(elem) {
    var acc = $(elem).attr("id").split("-")[1];
    $("#row-" + acc).removeClass("active");
}


function _tinx_target_plot2(json, selector) {
    if (!json) return;
    
    console.log(json);
    var seriesData = _.map(json.importances, function (elem) {
        return ({
            x: elem.dnovelty,
            y: elem.imp,
            dname: elem.dname,
            doid: elem.doid
        });
    });

    $(selector).highcharts({
        chart: {
	    height: 270,
            type: 'scatter',
            zoomType: 'xy'
        },
        credits: {enabled: false},
        title: {
            text: ""
        },
        xAxis: {
            type: 'logarithmic',
            title: {
                text: 'Novelty', enabled: true
            }
        },
        yAxis: {
            type: 'logarithmic',
            title: {
                text: 'Importance', enabled: true
            }
        },
        series: [{
            showInLegend: false,
            name: '',
            data: seriesData
        }],
        plotOptions: {
            column: {
                animation: false
            },
            series: {
                animation: false,
                cursor: 'pointer',
                point: {
                    events: {
                        click: function() {
                            location.href = '/idg/diseases/'+this.doid;
                        }
                    }
                }
            },
            scatter: {
                marker: {
                    radius: 5,
                    states: {
                        hover: {
                            enabled: true,
                            lineColor: 'rgb(10,10,10)'
                        }
                    }
                },
                states: {
                    hover: {
                        marker: {
                            enabled: false
                        }
                    }
                },
                tooltip: {
                    useHTML: true,
                    headerFormat: '',
                    pointFormatter: function() {
                        return("["+this.doid+"] <b>"+this.dname+"</b>");
                    }
                }
            }
        }

    });
}

function tinx_target_plot(selector, acc) {
    d3.json("/idg/tinx/target/" + acc, function (json) {
        _tinx_target_plot2(json, selector);
    });
}
