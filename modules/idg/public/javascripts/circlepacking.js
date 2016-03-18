/*
 * code shameless stolen from http://bl.ocks.org/mbostock/7607535
 */

function circlepacking (source, base) {
    var margin = 20,
	diameter = 760;
    
    var color = d3.scale.linear()
	.domain([-1, 5])
	.range(["hsl(180,80%,80%)", "hsl(240,30%,40%)"])
	.interpolate(d3.interpolateHcl);

    var pack = d3.layout.pack()
	.padding(2)
	.size([diameter - margin, diameter - margin])
	.value(function(d) { return d.size ? d.size : 100; })
    
    var svg = d3.select("body").append("svg")
	.attr("width", diameter)
	.attr("height", diameter)
	.append("g")
	.attr("transform", "translate(" + diameter / 2
	      + "," + diameter / 2 + ")");
    
    d3.json(source, function(error, root) {
	if (error) throw error;
	
	var focus = root,
	    nodes = pack.nodes(root),
	    view;

	var tooltip = d3.select("body")
	    .append("div")
	    .style("background", "white")
	    .style("border", "0px")
	    .style("border-radius", "8px")
	    .style("width", "200px")
	    .style("padding", "5px")
	    .style("opacity", 0.85)
	    .style("position", "absolute")
	    .style("z-index", "10")
	    .style("visibility", "hidden");
	
	var circle = svg.selectAll("circle")
	    .data(nodes)
	    .enter().append("circle")
	    .attr("class", function(d) {
		var leaf = d.tdl ? "node--leaf-"+d.tdl : "node--leaf";
		return d.parent ? d.children && d.children.length > 0
		    ? "node" : "node "+leaf : "node node--root"; })
	    .style("fill", function(d) {
		return d.children ? color(d.depth) : null; })
	    .on("mouseover", function(d) {
		return tooltip
		    .text(d.fullname ? d.fullname : d.name)
		    .style("visibility", "visible"); })
	    .on("mousemove", function () {
		return tooltip
		    .style("top", (d3.event.pageY-10)+"px")
		    .style("left", (d3.event.pageX+10)+"px");})
	    .on("mouseout", function () {
		return tooltip.style("visibility", "hidden"); })
	    .on("click", function(d) {
		if (focus !== d) zoom(d), d3.event.stopPropagation(); });
	
	var text = svg.selectAll("text")
	    .data(nodes)
	    .enter()
            .append("svg:a").attr("xlink:target","_blank")
	    .attr("xlink:href", function(d) {
		if (d.url) return d.url;
		var url;
		if (base) {
		    url = base;
		    url += base.indexOf('?') >= 0 ? '&q="' : '?q="';
		    url += d.name+'"';
		}
		return url; })
	    .append("text")
	    .attr("class", "label")
	    .style("font-size", function(d) {
		return d.depth > 1 ? "20px" : "14px"; })
	    .style("fill-opacity", function(d) {
		return d.parent === root ? 1 : 0; })
	    .style("display", function(d) {
		return d.parent === root ? "inline" : "none"; })
	    .text(function(d) {
		if (d.name.startsWith('Nuclear hormone'))
		    return 'Nuclear receptor';
		return d.name; });
	
	var node = svg.selectAll("circle,text");

	d3.select("body")
	    .style("background", color(-1))
	    .on("click", function() {
		zoom (root);
	    });
	
	zoomTo([root.x, root.y, root.r * 2 + margin]);

	function zoom(d) {
	    if (d.parent)
		zoom1(d);
	    else
		zoom0(d);
	}
	
	function zoom1(d) {
	    var focus0 = focus; focus = d;
	    
	    var transition = d3.transition()
		.duration(d3.event.altKey ? 7500 : 750)
		.tween("zoom", function(d) {
		    var i = d3.interpolateZoom
		    (view, [focus.x, focus.y, focus.r * 2 + margin]);
		    return function(t) { zoomTo(i(t)); };
		});
	    
	    transition.selectAll("text")
		.filter(function(d) {
		    return d === focus
			|| this.style.display === "inline"; })
		.style("fill-opacity", function(d) {
		    return d === focus ? 1 : 0; })
		.each("start", function(d) {
		    if (d === focus)
			this.style.display = "inline"; })
	        .each("end", function(d) {
		    if (d !== focus)
			this.style.display = "none"; });
	}

	function zoom0(d) {
	    var focus0 = focus; focus = d;
	    
	    var transition = d3.transition()
		.duration(d3.event.altKey ? 7500 : 750)
		.tween("zoom", function(d) {
		    var i = d3.interpolateZoom
		    (view, [focus.x, focus.y, focus.r * 2 + margin]);
		    return function(t) { zoomTo(i(t)); };
		});
	    
	    transition.selectAll("text")
		.filter(function(d) {
		    return d.parent === focus
			|| this.style.display === "inline"; })
		.style("fill-opacity", function(d) {
		    return d.parent === focus ? 1 : 0; })
		.each("start", function(d) {
		    if (d.parent === focus)
			this.style.display = "inline"; })
	        .each("end", function(d) {
		    if (d.parent !== focus)
			this.style.display = "none"; });
	}
	
	function zoomTo(v) {
	    var k = diameter / v[2]; view = v;
	    node.attr("transform", function(d) {
		return "translate(" + (d.x - v[0]) * k
		    + "," + (d.y - v[1]) * k + ")"; });
	    circle.attr("r", function(d) { return d.r * k; });
	}
    });
    
    d3.select(self.frameElement).style("height", diameter + "px");
}
