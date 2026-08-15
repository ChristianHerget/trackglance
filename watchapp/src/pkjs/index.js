/* Offline configuration for CoreApp. This file intentionally has no network dependencies. */
(function (root) {
  'use strict';
  var V = 3, MAX = 8, MAX_NAME = 20;
  var K = {v:0,type:1,index:30,count:31,data:32,id:33};
  var defaults = {
    theme: 'dark', selected: 0, profiles: [
      {name:'Hiking', locus:'Hiking', protected:true, metrics:[1,3,5,6,10,11]},
      {name:'Cycling', locus:'Cycling', protected:true, metrics:[1,3,5,6,7,11]},
      {name:'Running', locus:'Running', protected:true, metrics:[1,3,8,9,15,17]}
    ]
  };
  function clone(x) { return JSON.parse(JSON.stringify(x)); }
  function validName(x) { return typeof x === 'string' && x.trim().length > 0 && x.length <= MAX_NAME && !/[|\r\n]/.test(x); }
  function validate(c) {
    if (!c || (c.theme !== 'light' && c.theme !== 'dark') || !Array.isArray(c.profiles) ||
        c.profiles.length < 3 || c.profiles.length > MAX || c.selected < 0 || c.selected >= c.profiles.length) return false;
    var protectedNames = {Hiking:0,Cycling:0,Running:0}, names = {};
    var profilesValid = c.profiles.every(function(p) {
      if (!validName(p.name) || !validName(p.locus) || names[p.name.toLowerCase()] ||
          !Array.isArray(p.metrics) || p.metrics.length < 1 || p.metrics.length > 6) return false;
      names[p.name.toLowerCase()] = true;
      var seen = {};
      if (!p.metrics.every(function(m) { m=Number(m); if (m<1 || m>21 || seen[m]) return false; seen[m]=1; return true; })) return false;
      if (p.protected === true) {
        if (!(p.name in protectedNames)) return false;
        protectedNames[p.name]++;
      }
      return true;
    });
    return profilesValid && protectedNames.Hiking === 1 && protectedNames.Cycling === 1 && protectedNames.Running === 1;
  }
  function serialize(c) {
    if (!validate(c)) throw new Error('Invalid configuration');
    return [c.theme + '|' + c.selected].concat(c.profiles.map(function(p) {
      return [p.name,p.locus,p.protected?'1':'0',p.metrics.join(',')].join('|');
    })).join('\n');
  }
  function parse(s) {
    try {
      var lines=s.split('\n'), h=lines.shift().split('|'), c={theme:h[0],selected:Number(h[1]),profiles:[]};
      lines.forEach(function(line){var p=line.split('|');c.profiles.push({name:p[0],locus:p[1],protected:p[2]==='1',metrics:p[3].split(',').map(Number)});});
      return validate(c) ? c : null;
    } catch (_) { return null; }
  }
  function add(c, source) { if(c.profiles.length>=MAX)return false;var p=clone(source||{name:'Activity',locus:'',protected:false,metrics:[1,3]});p.protected=false;p.name=uniqueName(c,p.name);c.profiles.push(p);return true; }
  function uniqueName(c, base) { var n=base.slice(0,MAX_NAME),i=2;while(c.profiles.some(function(p){return p.name.toLowerCase()===n.toLowerCase();})){var s=' '+i++;n=base.slice(0,MAX_NAME-s.length)+s;}return n; }
  function remove(c,i){if(!c.profiles[i]||c.profiles[i].protected)return false;c.profiles.splice(i,1);c.selected=Math.min(c.selected,c.profiles.length-1);return true;}
  function rename(c,i,name){if(!c.profiles[i]||c.profiles[i].protected||!validName(name)||c.profiles.some(function(p,j){return j!==i&&p.name.toLowerCase()===name.toLowerCase();}))return false;c.profiles[i].name=name;return true;}
  function move(c,from,to){if(from<0||to<0||from>=c.profiles.length||to>=c.profiles.length)return false;var selected=c.profiles[c.selected],p=c.profiles.splice(from,1)[0];c.profiles.splice(to,0,p);c.selected=c.profiles.indexOf(selected);return true;}
  function send(c){var payload=serialize(c), chunks=payload.match(/[\s\S]{1,80}/g)||[''], id=Date.now()&0x7fffffff;chunks.forEach(function(x,i){var d={};d[K.v]=V;d[K.type]=5;d[K.index]=i;d[K.count]=chunks.length;d[K.data]=x;d[K.id]=id;Pebble.sendAppMessage(d);});}
  var metricNames=['Elapsed time','Moving time','Total distance','Moving distance','Current speed','Average speed','Max speed','Current pace','Average pace','Altitude','Ascent','Descent','Vertical speed','Slope','Average heart rate','Max heart rate','Average cadence','Max cadence','Average power','Max power','Energy'];
  function page(c, locusNames) {
    var state=encodeURIComponent(JSON.stringify(c)), locus=encodeURIComponent(JSON.stringify(locusNames||[]));
    var html='<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Locus Bridge</title><style>body{font:16px sans-serif;margin:16px;background:#f3f3f3;color:#111}section{background:white;padding:12px;margin:10px 0;border-radius:8px}label{display:block;margin:8px 0}input,select,button{font:inherit;max-width:100%}.metrics{columns:2}button{margin:4px;padding:7px}.save{width:100%;padding:12px}</style><h1>Locus Bridge</h1><label>Theme <select id="theme"><option>dark</option><option>light</option></select></label><div id="profiles"></div><button id="add">Add profile</button><button class="save" id="save">Save</button><script>'+
      'var c=JSON.parse(decodeURIComponent("'+state+'")),loc=JSON.parse(decodeURIComponent("'+locus+'")),mn='+JSON.stringify(metricNames)+';'+
      'var theme=document.getElementById("theme"),profiles=document.getElementById("profiles"),addButton=document.getElementById("add"),saveButton=document.getElementById("save");'+
      'function draw(){theme.value=c.theme;profiles.innerHTML="";c.profiles.forEach(function(p,i){var s=document.createElement("section");s.innerHTML="<label><input type=radio name=sel "+(c.selected===i?"checked":"")+"> Selected</label><label>Name <input class=name maxlength=20></label><label>Exact Locus profile <input class=locus maxlength=20 list=locusNames></label><div class=metrics></div><button type=button class=up>Up</button><button type=button class=down>Down</button>"+(p.protected?"":"<button type=button class=dup>Duplicate</button><button type=button class=del>Delete</button>");s.querySelector(".name").value=p.name;s.querySelector(".name").disabled=p.protected;s.querySelector(".locus").value=p.locus;var m=s.querySelector(".metrics");mn.forEach(function(n,j){m.innerHTML+="<label><input type=checkbox value="+(j+1)+" "+(p.metrics.indexOf(j+1)>=0?"checked":"")+">"+n+"</label>"});s.querySelector("[name=sel]").onclick=function(){c.selected=i};s.querySelector(".name").onchange=function(){p.name=this.value};s.querySelector(".locus").onchange=function(){p.locus=this.value};var metricInputs=s.querySelectorAll(".metrics input");for(var k=0;k<metricInputs.length;k++){metricInputs[k].onchange=function(){var checked=m.querySelectorAll(":checked"),values=[];for(var j=0;j<checked.length&&j<6;j++)values.push(+checked[j].value);p.metrics=values;draw()}}function mv(to){var selected=c.profiles[c.selected],q=c.profiles.splice(i,1)[0];c.profiles.splice(to,0,q);c.selected=c.profiles.indexOf(selected);draw()}s.querySelector(".up").onclick=function(){if(i)mv(i-1)};s.querySelector(".down").onclick=function(){if(i<c.profiles.length-1)mv(i+1)};var d=s.querySelector(".del");if(d)d.onclick=function(){var selected=c.profiles[c.selected];c.profiles.splice(i,1);c.selected=Math.max(0,c.profiles.indexOf(selected));draw()};var u=s.querySelector(".dup");if(u)u.onclick=function(){if(c.profiles.length<8){var q=JSON.parse(JSON.stringify(p));q.name=(q.name+" copy").slice(0,20);c.profiles.splice(i+1,0,q);draw()}};profiles.appendChild(s)});}' +
      'var dataList=document.createElement("datalist");dataList.id="locusNames";loc.forEach(function(x){var option=document.createElement("option");option.value=x;dataList.appendChild(option)});document.body.appendChild(dataList);function closeConfig(payload){if(typeof window.__pebbleConfigClose==="function")window.__pebbleConfigClose(payload);else location.href="pebblejs://close#"+encodeURIComponent(payload)}addButton.onclick=function(){if(c.profiles.length<8){c.profiles.push({name:"Activity",locus:"",protected:false,metrics:[1,3]});draw()}};saveButton.onclick=function(){c.theme=theme.value;if(c.profiles.some(function(p){return !p.name.trim()||!p.locus.trim()||p.metrics.length<1||p.metrics.length>6})){alert("Every profile needs a name, Locus mapping, and 1–6 metrics.");return;}closeConfig(JSON.stringify(c))};draw();<\/script>';
    return 'data:text/html;charset=utf-8,'+encodeURIComponent(html);
  }
  var profiles=[];
  if (typeof Pebble !== 'undefined') {
    Pebble.addEventListener('ready',function(){var c=parse(localStorage.getItem('config'))||clone(defaults);localStorage.setItem('config',serialize(c));send(c);var d={};d[K.v]=V;d[K.type]=7;Pebble.sendAppMessage(d);});
    Pebble.addEventListener('showConfiguration',function(){Pebble.openURL(page(parse(localStorage.getItem('config'))||clone(defaults),profiles));});
    Pebble.addEventListener('webviewclosed',function(e){if(!e.response)return;try{var c=JSON.parse(decodeURIComponent(e.response));if(validate(c)){localStorage.setItem('config',serialize(c));send(c);}}catch(_){}});
    Pebble.addEventListener('appmessage',function(e){var p=e.payload;if(p[K.type]===6&&typeof p[K.data]==='string'){profiles=profiles.concat(p[K.data].split('\n').filter(Boolean));}});
  }
  if (typeof module !== 'undefined') module.exports={defaults:defaults,validate:validate,serialize:serialize,parse:parse,add:add,remove:remove,rename:rename,move:move,page:page};
})(this);
