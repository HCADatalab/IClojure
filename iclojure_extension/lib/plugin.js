//import { IJupyterWidgetRegistry } from '@jupyter-widgets/base';
//import { ISettingRegistry } from "@jupyterlab/coreutils";
(function(){
var core = require('@jupyterlab/coreutils');
var mime = require('@jupyterlab/rendermime');
var widgets = require('@phosphor/widgets');
console.log('WIDGETS', widgets);
console.log('CORE', core);
console.log('MIME', mime);
 
class OutputWidget extends widgets.Widget {
  /**
   * Construct a new output widget.
   */
  constructor(options, shared_state) {
    super();
    this._options = options;
    this._state = shared_state;
    this._mimeType = options.mimeType;
    // this.addClass(CLASS_NAME);
  }

  /**
   * Render CLJ into this widget's node.
   */
  renderModel(model) {
    console.log('options', this._options, model);
    this.node.innerHTML = "<div class=iclj>" + model.data["text/iclojure-html"] + "</div>";
    console.log("ul", this.node.firstElementChild.firstElementChild);
    pushTrails(this.node.firstElementChild.firstElementChild);
    this.node.onclick = click_handler(this._state);
    return Promise.resolve(undefined);
  }
}

function ensure_comm(state) {
  if (state.comm) return state.comm;
  const comm = state.comm = state.context.session.kernel.connectToComm("expansion");
  comm.onMsg = function(msg) {
    const expr = msg.content.data["elision-id"];
    const html = msg.content.data.expansion;
    const elt = state.pending[expr];
    const pelt = elt.parentNode;
    const div = document.createElement('div');
    div.innerHTML = html;
    console.log('html', html);
    let nnode;
    for(let node = div.firstElementChild.firstElementChild.firstElementChild.firstChild;
        node && node.classList && !node.classList.contains('trail');
        node = nnode) {
      nnode = node.nextSibling;
      pelt.insertBefore(node, elt);
    }
    pelt.removeChild(elt);
    pushTrails(elt.closest('.iclj'));
  };
  comm.open();
  return comm;
}

const click_handler = (state) => function(e) {
  e.stopPropagation();
  let elt = e.target;
  while(elt.tagName !== "LI") elt = elt.parentElement;
  if (!elt) return;
  if (elt.classList.contains("elision")) {
    let expr =  elt.dataset.expr;
    state.pending[expr]=elt;
    ensure_comm(state).send({"elision-id": expr});
    return;
  }
  
  while(elt.tagName !== "LI" || !elt.firstElementChild) elt = elt.parentElement;
  if (!elt) return;
  if (elt.classList.contains("expanded")) {
    elt.classList.remove("expanded");
    while(true) {
      let n = 0;
      for(let sib = elt.parentElement.firstElementChild; sib; sib = sib.nextElementSibling) {
        if (sib.classList.contains("contains-expanded") || sib.classList.contains("expanded")) n++; 
      }
      if (n > 0) break;
      let anc = elt.parentElement.parentElement;
      if (anc.tagName !== "LI") break;
      anc.classList.remove("contains-expanded");
      elt = anc;
    }
  } else {
    elt.classList.add("expanded");
    for(let anc = elt.parentElement.parentElement; anc !== this; anc = anc.parentElement.parentElement) {
      anc.classList.add("contains-expanded");
    }
  }
}


function div(s) {
  let div = document.createElement('div');
  div.innerHTML = s;
  return div;
}

function pushTrails(root_ul) {
  let ul = root_ul;
  while(true) {
    let li = ul.lastElementChild;
    for(; li && li.classList.contains('trail'); li = li.previousElementSibling);
    let new_ul = li && li.firstElementChild;
    for(li = li && li.previousElementSibling; li; li = li.previousElementSibling)
      li.firstElementChild && pushTrails(li.firstElementChild);
    if (!new_ul) break;
    ul = new_ul;
  }
  const target = ul;
  if (target === root_ul) return;
  for(;ul !== root_ul; ul = ul.parentElement.parentElement)
    for(let li = ul.parentElement.nextElementSibling; li; li = li.nextElementSibling)
      target.appendChild(li);
}

// jupiler.session.kernelChanged.connect(function() { console.log("new kernel") });
// window.jupiler.session.kernel.connectToComm("unrepl2");

const style='<style id=iclojure-style>\
  .iclj {\
    font-family: monospace;\
  }\
  .iclj * { padding: 0; margin: 0; }\
  /* collapse expand */\
  .iclj ul{\
    display: inline-block;\
    vertical-align: top;\
    white-space: normal;\
  }\
  .iclj li.expanded + li::before {\
    content:"\\A";\
    white-space: pre;\
  }\
  .iclj li.expanded > ul > li + li.trail::before {\
    content:"";\
  }\
  .iclj li{\
    display: inline;\
    vertical-align: top;\
    white-space: pre;\
  }\
  .iclj li.space::before {\
    content: "\\A";\
    white-space: normal;\
  }\
  .iclj li.expanded > ul > li.space::before {\
    white-space: pre;\
  }\
  .iclj li.map > ul > li.space::before {\
    content: ",\\A";\
  }\
  .iclj li:first-child, .iclj li.trail, .iclj li.expanded > ul > li, .iclj li.expanded + li {\
    padding-left: 0;\
  }\
  /*  */\
  .iclj .elision {\
    cursor: pointer;\
  }\
  .iclj .elision-deadend {\
    color: red;\
    cursor: not-allowed;\
  }\
</style>';

module.exports = [{
    id: 'iclojure_extension',
    autoStart: true,
    requires: [mime.IRenderMimeRegistry],
    activate: function(app, reg) {
      console.log('JupyterLab extension iclojure_extension is activated!');
      console.log('args', app, reg);
      let shared_state = {pending: {}}; // use a promise? lifecycles are not clear
      window.jupiler = shared_state;
console.log("before style");
      document.head.insertAdjacentHTML('beforeend', style);
console.log("after style");
      reg.addFactory({
        safe: true,
        mimeTypes: ["text/iclojure-html"],
        createRenderer: options => new OutputWidget(options, shared_state)
      });
      app.docRegistry.addWidgetExtension('Notebook', {createNew: function(a, context) { shared_state.context = context; }});
    }
}];
})();
