function gui(){var O='bootstrap',P='begin',Q='gwt.codesvr.gui=',R='gwt.codesvr=',S='gui',T='startup',U='DUMMY',V=0,W=1,X='iframe',Y='javascript:""',Z='position:absolute; width:0; height:0; border:none; left: -1000px;',$=' top: -1000px;',_='CSS1Compat',ab='<!doctype html>',bb='',cb='<html><head><\/head><body><\/body><\/html>',db='undefined',eb='DOMContentLoaded',fb=50,gb='Chrome',hb='eval("',ib='");',jb='script',kb='javascript',lb='moduleStartup',mb='moduleRequested',nb='Failed to load ',ob='head',pb='meta',qb='name',rb='gui::',sb='::',tb='gwt:property',ub='content',vb='=',wb='gwt:onPropertyErrorFn',xb='Bad handler "',yb='" for "gwt:onPropertyErrorFn"',zb='gwt:onLoadErrorFn',Ab='" for "gwt:onLoadErrorFn"',Bb='#',Cb='?',Db='/',Eb='img',Fb='clear.cache.gif',Gb='baseUrl',Hb='gui.nocache.js',Ib='base',Jb='//',Kb='clipboardData.access',Lb='window',Mb='event',Nb='gecko.variant',Ob='user.agent',Pb='gecko1_8',Qb='none',Rb='trident',Sb='ie11',Tb='std',Ub=2,Vb='onLoad',Wb='default',Xb='webkit',Yb='safari',Zb='msie',$b=10,_b=11,ac='ie10',bc=9,cc='ie9',dc=8,ec='ie8',fc='gecko',gc=3,hc=4,ic='selectingPermutation',jc='gui.devmode.js',kc='4272CCB3D7397C740AC6689AAB43D2A0',lc='foobar',mc=':1',nc=':2',oc=':3',pc='AF2B3C077C308458CAD6ACC19F925117',qc='C5872A2F11DEE032C6746E7B9A3C1528',rc='C73A5CE91AA8EFF3C09B81052C724842',sc=':4',tc=':5',uc=':6',vc=':7',wc='F02642E6782FDEA0D815386C089EA802',xc=':',yc='.cache.js',zc='link',Ac='rel',Bc='stylesheet',Cc='href',Dc='loadExternalRefs',Ec='css/auto/clean.css',Fc='css/auto/gui.css',Gc='end',Hc='http:',Ic='file:',Jc='_gwt_dummy_',Kc='__gwtDevModeHook:gui',Lc='Ignoring non-whitelisted Dev Mode URL: ',Mc=':moduleBase';var o=window;var p=document;r(O,P);function q(){var a=o.location.search;return a.indexOf(Q)!=-1||a.indexOf(R)!=-1}
function r(a,b){if(o.__gwtStatsEvent){o.__gwtStatsEvent({moduleName:S,sessionId:o.__gwtStatsSessionId,subSystem:T,evtGroup:a,millis:(new Date).getTime(),type:b})}}
gui.__sendStats=r;gui.__moduleName=S;gui.__errFn=null;gui.__moduleBase=U;gui.__softPermutationId=V;gui.__computePropValue=null;gui.__getPropMap=null;gui.__installRunAsyncCode=function(){};gui.__gwtStartLoadingFragment=function(){return null};gui.__gwt_isKnownPropertyValue=function(){return false};gui.__gwt_getMetaProperty=function(){return null};var s=null;var t=o.__gwt_activeModules=o.__gwt_activeModules||{};t[S]={moduleName:S};gui.__moduleStartupDone=function(e){var f=t[S].bindings;t[S].bindings=function(){var a=f?f():{};var b=e[gui.__softPermutationId];for(var c=V;c<b.length;c++){var d=b[c];a[d[V]]=d[W]}return a}};var u;function v(){w();return u}
function w(){if(u){return}var a=p.createElement(X);a.src=Y;a.id=S;a.style.cssText=Z+$;a.tabIndex=-1;p.body.appendChild(a);u=a.contentDocument;if(!u){u=a.contentWindow.document}u.open();var b=document.compatMode==_?ab:bb;u.write(b+cb);u.close()}
function A(k){function l(a){function b(){if(typeof p.readyState==db){return typeof p.body!=db&&p.body!=null}return /loaded|complete/.test(p.readyState)}
var c=b();if(c){a();return}function d(){if(!c){c=true;a();if(p.removeEventListener){p.removeEventListener(eb,d,false)}if(e){clearInterval(e)}}}
if(p.addEventListener){p.addEventListener(eb,d,false)}var e=setInterval(function(){if(b()){d()}},fb)}
function m(c){function d(a,b){a.removeChild(b)}
var e=v();var f=e.body;var g;if(navigator.userAgent.indexOf(gb)>-1&&window.JSON){var h=e.createDocumentFragment();h.appendChild(e.createTextNode(hb));for(var i=V;i<c.length;i++){var j=window.JSON.stringify(c[i]);h.appendChild(e.createTextNode(j.substring(W,j.length-W)))}h.appendChild(e.createTextNode(ib));g=e.createElement(jb);g.language=kb;g.appendChild(h);f.appendChild(g);d(f,g)}else{for(var i=V;i<c.length;i++){g=e.createElement(jb);g.language=kb;g.text=c[i];f.appendChild(g);d(f,g)}}}
gui.onScriptDownloaded=function(a){l(function(){m(a)})};r(lb,mb);var n=p.createElement(jb);n.src=k;if(gui.__errFn){n.onerror=function(){gui.__errFn(S,new Error(nb+code))}}p.getElementsByTagName(ob)[V].appendChild(n)}
gui.__startLoadingFragment=function(a){return D(a)};gui.__installRunAsyncCode=function(a){var b=v();var c=b.body;var d=b.createElement(jb);d.language=kb;d.text=a;c.appendChild(d);c.removeChild(d)};function B(){var c={};var d;var e;var f=p.getElementsByTagName(pb);for(var g=V,h=f.length;g<h;++g){var i=f[g],j=i.getAttribute(qb),k;if(j){j=j.replace(rb,bb);if(j.indexOf(sb)>=V){continue}if(j==tb){k=i.getAttribute(ub);if(k){var l,m=k.indexOf(vb);if(m>=V){j=k.substring(V,m);l=k.substring(m+W)}else{j=k;l=bb}c[j]=l}}else if(j==wb){k=i.getAttribute(ub);if(k){try{d=eval(k)}catch(a){alert(xb+k+yb)}}}else if(j==zb){k=i.getAttribute(ub);if(k){try{e=eval(k)}catch(a){alert(xb+k+Ab)}}}}}__gwt_getMetaProperty=function(a){var b=c[a];return b==null?null:b};s=d;gui.__errFn=e}
function C(){function e(a){var b=a.lastIndexOf(Bb);if(b==-1){b=a.length}var c=a.indexOf(Cb);if(c==-1){c=a.length}var d=a.lastIndexOf(Db,Math.min(c,b));return d>=V?a.substring(V,d+W):bb}
function f(a){if(a.match(/^\w+:\/\//)){}else{var b=p.createElement(Eb);b.src=a+Fb;a=e(b.src)}return a}
function g(){var a=__gwt_getMetaProperty(Gb);if(a!=null){return a}return bb}
function h(){var a=p.getElementsByTagName(jb);for(var b=V;b<a.length;++b){if(a[b].src.indexOf(Hb)!=-1){return e(a[b].src)}}return bb}
function i(){var a=p.getElementsByTagName(Ib);if(a.length>V){return a[a.length-W].href}return bb}
function j(){var a=p.location;return a.href==a.protocol+Jb+a.host+a.pathname+a.search+a.hash}
var k=g();if(k==bb){k=h()}if(k==bb){k=i()}if(k==bb&&j()){k=e(p.location.href)}k=f(k);return k}
function D(a){if(a.match(/^\//)){return a}if(a.match(/^[a-zA-Z]+:\/\//)){return a}return gui.__moduleBase+a}
function F(){var f=[];var g=V;function h(a,b){var c=f;for(var d=V,e=a.length-W;d<e;++d){c=c[a[d]]||(c[a[d]]=[])}c[a[e]]=b}
var i=[];var j=[];function k(a){var b=j[a](),c=i[a];if(b in c){return b}var d=[];for(var e in c){d[c[e]]=e}if(s){s(a,d,b)}throw null}
j[Kb]=function(){if(!!window.clipboardData){return Lb}else{return Mb}};i[Kb]={event:V,window:W};j[Nb]=function(){try{if(!j.hasOwnProperty(Ob)||k(Ob)!==Pb){return Qb}}catch(a){return Qb}if(navigator.userAgent.toLowerCase().indexOf(Rb)!=-1){return Sb}return Tb};i[Nb]={ie11:V,none:W,std:Ub};j[Vb]=function(){if(!o.marvin){o.marvin={onLoadArray:[],onLoad:function(){if(!o.marvin.onLoadArray){return}for(var a=V;a<o.marvin.onLoadArray.length;a++)o.marvin.onLoadArray[a]();delete o.marvin.onLoadArray},onReady:function(a){o.marvin.onLoadArray?o.marvin.onLoadArray.push(a):a()}}}return Wb};i[Vb]={'default':V,foobar:W};j[Ob]=function(){var a=navigator.userAgent.toLowerCase();var b=p.documentMode;if(function(){return a.indexOf(Xb)!=-1}())return Yb;if(function(){return a.indexOf(Zb)!=-1&&(b>=$b&&b<_b)}())return ac;if(function(){return a.indexOf(Zb)!=-1&&(b>=bc&&b<_b)}())return cc;if(function(){return a.indexOf(Zb)!=-1&&(b>=dc&&b<_b)}())return ec;if(function(){return a.indexOf(fc)!=-1||b>=_b}())return Pb;return Yb};i[Ob]={gecko1_8:V,ie10:W,ie8:Ub,ie9:gc,safari:hc};__gwt_isKnownPropertyValue=function(a,b){return b in i[a]};gui.__getPropMap=function(){var a={};for(var b in i){if(i.hasOwnProperty(b)){a[b]=k(b)}}return a};gui.__computePropValue=k;o.__gwt_activeModules[S].bindings=gui.__getPropMap;r(O,ic);if(q()){return D(jc)}var l;try{h([Mb,Qb,Wb,ec],kc);h([Mb,Qb,lc,ec],kc+mc);h([Lb,Qb,Wb,ec],kc+nc);h([Lb,Qb,lc,ec],kc+oc);h([Mb,Qb,Wb,ac],pc);h([Mb,Qb,lc,ac],pc+mc);h([Lb,Qb,Wb,ac],pc+nc);h([Lb,Qb,lc,ac],pc+oc);h([Mb,Qb,Wb,cc],qc);h([Mb,Qb,lc,cc],qc+mc);h([Lb,Qb,Wb,cc],qc+nc);h([Lb,Qb,lc,cc],qc+oc);h([Mb,Sb,Wb,Pb],rc);h([Mb,Tb,Wb,Pb],rc+mc);h([Mb,Sb,lc,Pb],rc+nc);h([Mb,Tb,lc,Pb],rc+oc);h([Lb,Sb,Wb,Pb],rc+sc);h([Lb,Tb,Wb,Pb],rc+tc);h([Lb,Sb,lc,Pb],rc+uc);h([Lb,Tb,lc,Pb],rc+vc);h([Mb,Qb,Wb,Yb],wc);h([Mb,Qb,lc,Yb],wc+mc);h([Lb,Qb,Wb,Yb],wc+nc);h([Lb,Qb,lc,Yb],wc+oc);l=f[k(Kb)][k(Nb)][k(Vb)][k(Ob)];var m=l.indexOf(xc);if(m!=-1){g=parseInt(l.substring(m+W),$b);l=l.substring(V,m)}}catch(a){}gui.__softPermutationId=g;return D(l+yc)}
function G(){if(!o.__gwt_stylesLoaded){o.__gwt_stylesLoaded={}}function c(a){if(!__gwt_stylesLoaded[a]){var b=p.createElement(zc);b.setAttribute(Ac,Bc);b.setAttribute(Cc,D(a));p.getElementsByTagName(ob)[V].appendChild(b);__gwt_stylesLoaded[a]=true}}
r(Dc,P);c(Ec);c(Fc);r(Dc,Gc)}
B();gui.__moduleBase=C();t[S].moduleBase=gui.__moduleBase;var H=F();if(o){var I=!!(o.location.protocol==Hc||o.location.protocol==Ic);o.__gwt_activeModules[S].canRedirect=I;function J(){var b=Jc;try{o.sessionStorage.setItem(b,b);o.sessionStorage.removeItem(b);return true}catch(a){return false}}
if(I&&J()){var K=Kc;var L=o.sessionStorage[K];if(!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/.*$/.test(L)){if(L&&(window.console&&console.log)){console.log(Lc+L)}L=bb}if(L&&!o[K]){o[K]=true;o[K+Mc]=C();var M=p.createElement(jb);M.src=L;var N=p.getElementsByTagName(ob)[V];N.insertBefore(M,N.firstElementChild||N.children[V]);return false}}}G();r(O,Gc);A(H);return true}
gui.succeeded=gui();