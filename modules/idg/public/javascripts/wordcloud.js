function wordcloud (id, width, height, url, data) {
    var fill = d3.scale.category20();

    d3.layout.cloud().size([width, height])
        .words(data)
        .padding(5)
        .rotate(function(d) {
            var h = 0;
            for (var i = 0; i < d.text.length; ++i) {
                h ^= d.text.charCodeAt(i);
            }
            return (~~h % 2) * 90;
            //return ~~(Math.random() * 2) * 90;
        })
        .font("Impact")
        .fontSize(function(d) {
            if (d.size < 10) return 15;
            return d.size;
        })
        .on("end", draw)
        .start();

    function draw(words) {
        d3.select('#'+id).append("svg")
            .attr("width", width)
            .attr("height", height)
            .append("g")
            .attr("transform", "translate("+width/2+","+height/2+")")
            .selectAll("text")
            .data(words)
            .enter()
            .append("svg:a").attr("xlink:href", function(d){
                return url+(url.indexOf('?') > 0 ? '&' : '?')+'q='+d.text;
            })
            .append("text")
            .style("font-size", function(d) { return d.size + "px"; })
            .style("font-family", "Impact")
            .style("fill", function(d, i) { return fill(i); })
            .attr("text-anchor", "middle")
            .attr("transform", function(d) {
                return "translate(" + [d.x, d.y] + ")rotate(" + d.rotate + ")";
            } )
            .text(function(d) { return d.text; });
    }
}
