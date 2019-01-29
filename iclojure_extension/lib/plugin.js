//import { IJupyterWidgetRegistry } from '@jupyter-widgets/base';
//import { ISettingRegistry } from "@jupyterlab/coreutils";
(function(){
var core = require('@jupyterlab/coreutils');
var mime = require('@jupyterlab/rendermime');
var widgets = require('@phosphor/widgets');
var notebook = require('@jupyterlab/notebook');
 
class OutputWidget extends widgets.Widget {
  /**
   * Construct a new output widget.
   */
  constructor(options, shared_state) {
    console.log("OutputWidget", options);
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
    this.node.innerHTML = "<div class=iclj>" + model.data["text/iclojure-html"] + "</div>";
    pushTrails(this.node.firstElementChild.firstElementChild);
    this.node.onclick = click_handler(elision_resolver(this._state));
    return Promise.resolve(undefined);
  }
}

const elision_resolver = (state) => function(expr, elt) {
  const currnb = state.notebooks.currentWidget;
  const uninitialized = !state.comms[currnb.id];
  const {comm, pending} = state.comms[currnb.id] || (state.comms[currnb.id] = {comm: currnb.session.kernel.connectToComm("expansion"), pending: {}});
  if (uninitialized) {
    comm.onMsg = function(msg) {
      const expr = msg.content.data["elision-id"];
      const html = msg.content.data.expansion;
      const elt = pending[expr];
      const pelt = elt.parentNode;
      const div = document.createElement('div');
      div.innerHTML = html;
      let nnode;
      let pnode = div.firstElementChild.firstElementChild.firstElementChild;
      if (elt.previousElementSibling && elt.previousElementSibling.classList.contains('browser')
          && pnode.firstElementChild && pnode.firstElementChild.classList.contains('seq'))
        pnode = pnode.firstElementChild.firstElementChild;
      for(let node = pnode.firstChild;
          node && node.classList && !node.classList.contains('trail');
          node = nnode) {
        nnode = node.nextSibling;
        pelt.insertBefore(node, elt);
      }
      pelt.removeChild(elt);
      pushTrails(pelt.closest('.iclj > ul'));
    };
    comm.open();
  }
  pending[expr] = elt;
  comm.send({"elision-id": expr});
}

function isTrail(li, ifEmpty) {
  return li ? li.classList.contains('trail') : ifEmpty;
}

function expand(li, root) {
  li.classList.add("expanded");
  for(let anc = li.parentElement.parentElement; anc !== root; anc = anc.parentElement.parentElement)
    anc.classList.add("contains-expanded");
}

function unexpand(li) {
  li.classList.remove("expanded");
  while(true) {
    let n = 0;
    for(let sib = li.parentElement.firstElementChild; sib; sib = sib.nextElementSibling) {
      if (sib.classList.contains("contains-expanded") || sib.classList.contains("expanded")) n++; 
    }
    if (n > 0) break;
    let anc = li.parentElement.parentElement;
    if (anc.tagName !== "LI") break;
    anc.classList.remove("contains-expanded");
    li = anc;
  }
}

function collapse(li) {
  ensureTrail(li.firstElementChild);
  li.classList.add("collapsed");
}

const click_handler = (resolve) => function(e) {
  e.stopPropagation();
  const root_li =  this.firstElementChild;
  let elt = e.target;
  if (elt.tagName !== "LI") return;
  if (elt.classList.contains("browser")) {
    if (elt.classList.contains("expanded")) {
      unexpand(elt);
      ensureTrail(elt.parentElement);
      return;
    }
    expand(elt, root_li);
    pushTrails(elt.parentElement, true);
    elt = elt.nextElementSibling;
    if (elt.classList.contains("elision"))
      resolve(elt.dataset.expr, elt);
    return;
  }
  if (elt.classList.contains("elision")) {
    resolve(elt.dataset.expr, elt);
    return;
  }
  if (elt.parentElement.parentElement.classList.contains('collapsed'))
    elt = elt.parentElement.parentElement;

  if (!elt.firstElementChild) return;
  while(elt.tagName !== "LI" || !elt.firstElementChild) elt = elt.parentElement;
  if (!elt) return;
  if (elt.classList.contains("expanded")) { // expanded => at least two items
    collapse(elt);
    unexpand(elt);
  } else if (elt.classList.contains("collapsed")) {
    elt.classList.remove("collapsed");
    pushTrails(elt.firstElementChild, true);
  } else if (elt.firstElementChild && !isTrail(elt.firstElementChild.children.item(1), true)) {
    expand(elt, root_li);
  } else if (elt.firstElementChild && !isTrail(elt.firstElementChild.firstElementChild, true)) {
    collapse(elt);
  }
}

function pushTrails(root_ul, only_own_trail) {
  if (!root_ul) return;
  let ul = root_ul;
  while(true) {
    let li = ul.lastElementChild;
    for(; li && li.classList.contains('trail'); li = li.previousElementSibling);
    let new_ul = li && !li.parentElement.parentElement.classList.contains('collapsed') && li.firstElementChild;
    if (!only_own_trail)
      for(li = li && li.previousElementSibling; li; li = li.previousElementSibling)
        pushTrails(li.firstElementChild, false);
    if (!new_ul) break;
    ul = new_ul;
  }
  const target = ul;
  if (target === root_ul) return;
  for(let depth = 1;ul !== root_ul; depth++, ul = ul.parentElement.parentElement)
    for(let li = ul.parentElement.nextElementSibling; li; li = li.nextElementSibling) {
      li.dataset.depth = trailDepth(li) + depth;
      target.appendChild(li);
    }
}

function trailDepth(li) {
  return parseInt(li.dataset.depth || "0");
}

function ensureTrail(root_ul) {
  let ul = root_ul;
  let depth = 0;
  while(ul) {
    let li = ul.lastElementChild;
    if (!li) return;
    if (li.classList.contains('trail')) {
      let prevli = null;
      while(li && li.classList.contains('trail') && trailDepth(li) >= depth) {
        li.dataset.depth = trailDepth(li) - depth;
        let nextli = li.previousElementSibling;
        root_ul.insertBefore(li, prevli);
        prevli = li;
        li = nextli;
      }
      return;
    }
    ul = li.firstElementChild;
    depth++;
  }
}

const style='<style id=iclojure-style>\
  .iclj {\
    font-family: monospace;\
  }\
  .iclj .class {color: orange;}\
  .iclj.iclj  .keyword {color: teal; font-weight: bold;}\
  .iclj * { padding: 0; margin: 0; }\
  /* collapse expand */\
  .iclj li {\
    cursor: default;\
  }\
  .iclj li:first-child {\
    cursor: pointer;\
  }\
  .iclj ul{\
    display: inline-block;\
    vertical-align: top;\
    white-space: pre-line;\
  }\
  .iclj li.expanded + li.space, .iclj li.contains-expanded + li.space  {\
    white-space: normal;\
  }\
  .iclj li.expanded + li.space::after, .iclj li.contains-expanded + li.space::after  {\
    content:"\\A";\
  }\
  .iclj li.collapsed > ul > li {\
    display: none;\
  }\
  .iclj li.collapsed > ul::before {\
    content: "\\22EF";\
    font-weight: bold;\
    color: #aaa;\
  }\
  .iclj li.collapsed > ul > li.trail {\
    display: inline;\
  }\
  .iclj li{\
    display: inline;\
    vertical-align: top;\
    white-space: pre;\
  }\
  .iclj li.space {\
    white-space: pre-line;\
  }\
  .iclj li.string > ul > li {\
    white-space: pre-wrap;\
  }\
  .iclj li.expanded > ul > li.space::after {\
    content: "\\A";\
  }\
  .iclj li.ns::before {\
    content: "ns";\
    vertical-align: top;\
    font-size: 50%;\
  }\
  .iclj li.browser::before {\
    content: "\\1F50D";\
    font-size: 50%;\
  }\
  .iclj li.browser + li {\
    display: none;\
  }\
  .iclj li.browser.expanded::before {\
    content: "\\1F50E";\
  }\
  .iclj li.browser.expanded + li {\
    display: inline;\
  }\
  .iclj li.browser.expanded ~ li::before {\
    content:"\\A\\00A0\\00A0";\
    white-space: pre;\
  }\
  /*  */\
  .iclj .elision {\
    cursor: pointer;\
    color: blue;\
    text-decoration: underline;\
  }\
  .iclj .elision-deadend {\
    color: red;\
    cursor: not-allowed;\
  }\
</style>';
module.exports = [{
    id: 'iclojure_extension',
    autoStart: true,
    requires: [mime.IRenderMimeRegistry, notebook.INotebookTracker],
    activate: function(app, reg, notebooks) {
      console.log('JupyterLab extension iclojure_extension is activated!');
      let shared_state = {comms: {}, notebooks: notebooks}; 
      window.jupiler = shared_state;
      document.head.insertAdjacentHTML('beforeend', style);
      reg.addFactory({
        safe: true,
        mimeTypes: ["text/iclojure-html"],
        createRenderer: options => new OutputWidget(options, shared_state)
      });
    }
}];
})();
