"use strict";
const $ = (id) => document.getElementById(id);
const model = { state: null, sessions: [], selected: null, stage: "result.png", mode: "compare", zoom: "fit", revision: -1, loading: false };
const steps = [["capture","Capture","Two physical camera inputs"],["prepare","Prepare","Orientation & lens geometry"],["align","Align reference","Reliable overlap & confidence"],["fusion","OpenCV fusion","Transfer trusted detail"],["ai","AI restoration","Optional on-device enhancement"],["finalize","Finalize","Stage files & phone preview"]];
function el(tag, cls, text) { const node=document.createElement(tag); if(cls)node.className=cls; if(text!==undefined)node.textContent=text; return node; }
function icon(name) { const svg=document.createElementNS("http://www.w3.org/2000/svg","svg"); const use=document.createElementNS(svg.namespaceURI,"use"); use.setAttribute("href","#i-"+name); svg.append(use); return svg; }
function show(node, visible) { node.classList.toggle("hidden",!visible); }
function duration(value) { return value==null?"—":value<1000?Math.round(value)+" ms":(value/1000).toFixed(1)+" s"; }
function bytes(value) { if(value==null)return "—"; return value>=1e9?(value/1e9).toFixed(1)+" GB":value>=1e6?(value/1e6).toFixed(1)+" MB":(value/1e3).toFixed(1)+" KB"; }
function stamp(value) { return new Date(value).toLocaleString("en-IN",{day:"2-digit",month:"short",hour:"2-digit",minute:"2-digit",timeZone:"Asia/Kolkata"}); }
function number(value, digits=2) { return value==null?"—":Number(value).toFixed(digits); }
function psnr(row) { return !row?"—":row.perfectMatch?"∞":number(row.psnrDb)+" dB"; }
function apiPath(id,suffix="") { return "/api/sessions/"+encodeURIComponent(id)+suffix; }
function imagePath(file) { return apiPath(model.selected.id,"/image/"+encodeURIComponent(file)); }
let toastTimer;
function toast(text,error=false) { const node=$("toast"); node.textContent=text; node.classList.toggle("error",error); show(node,true); clearTimeout(toastTimer); toastTimer=setTimeout(()=>show(node,false),5000); }
async function request(path,options) { const response=await fetch(path,options); const result=await response.json(); if(!response.ok)throw new Error(result.error||"Request failed"); return result; }
function jsonPost(path,data) { return request(path,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(data)}); }
function navigate() { const key=["workspace","sessions","metrics","settings"].includes(location.hash.slice(1))?location.hash.slice(1):"workspace"; document.querySelectorAll(".page").forEach(n=>show(n,n.id==="page-"+key)); document.querySelectorAll("[data-nav]").forEach(n=>n.classList.toggle("active",n.dataset.nav===key)); $("breadcrumb").textContent={workspace:"Overview",sessions:"Capture sessions",metrics:"Quality matrix",settings:"Settings"}[key]; }
window.addEventListener("hashchange",navigate);
function renderState(state) {
 model.state=state;
 const pill=$("device-pill"); const dot=pill.querySelector(".dot");
 dot.className="dot"+(state.ready?" green":state.device?" amber":"");
 pill.lastElementChild.textContent=state.device?(state.device.model+" · "+(state.ready?"USB ready":state.connection==="unauthorized"?"Allow debugging":state.connection==="app-required"?"Open updated app":"Syncing")):"USB disconnected";
 $("settings-connection").textContent=state.message||"Checking connection…";
 $("archive-path").textContent=state.archivePath||"—";
 $("settings-space").textContent=bytes(state.diskFreeBytes);
 $("settings-package").textContent=state.package||"—";
 $("archive-enabled").checked=!state.paused;
 $("footer-status").textContent=state.ready?"USB companion ready · files remain local":state.message||"Local companion";
 const select=$("device-select");
 if(document.activeElement!==select){ select.replaceChildren(); (state.devices||[]).forEach(device=>{const option=el("option","",device.model+" · "+device.state);option.value=device.serial;option.selected=state.device?.serial===device.serial;select.append(option);}); if(!select.options.length){select.append(el("option","","No phone connected"));select.disabled=true;}else select.disabled=false; }
 renderPipeline(state); renderTransfer(state); renderActivity(state.events||[]);
 if(state.archiveRevision!==model.revision&&!model.loading)refreshSessions(state.archiveRevision);
 const currentJob=model.selected&&state.analysisJobs?.[model.selected.id];
 if(currentJob?.status==="running"){ const strong=$("quality-notice").querySelector("strong");strong.textContent="Calculating against your ground truth…"; }
 if(currentJob?.status==="error"&&$("quality-notice").dataset.error!==currentJob.error){$("quality-notice").dataset.error=currentJob.error;toast(currentJob.error,true);renderMetrics();}
}
async function refreshSessions(revision) {
 model.loading=true;
 try{
  const sessions=await request("/api/sessions"); model.sessions=sessions; model.revision=revision;
  $("nav-count").textContent=sessions.length; $("stat-sessions").textContent=sessions.length; $("library-count").textContent=sessions.length+" sessions";
  const latest=sessions[0]; $("stat-capture").textContent=duration(latest?.captureMs); $("stat-processing").textContent=duration(latest?.processingMs);
  $("stat-coverage").textContent=latest?.fusion?.referenceAreaPercent==null?"—":number(latest.fusion.referenceAreaPercent,1)+"%";
  $("stat-capture-foot").textContent=latest?(latest.synthetic?"Synthetic verification fixture":latest.captureMode):"Waiting for an approved capture";
  if(!model.selected&&latest)selectSession(latest.id);
  else if(model.selected){ const fresh=sessions.find(s=>s.id===model.selected.id);if(fresh){model.selected=fresh;renderMetrics();} }
  renderLibrary();renderMatrix();
 }catch(error){toast(error.message,true);}finally{model.loading=false;}
}
function renderPipeline(state) {
 const phone=state.ready?state.phone:null; const live=phone?.sessionId&&phone?.phase!=="camera";
 const timings=live?phone.stageTimingsMs||{}:model.selected?.stageTimings||{};
 const current=live?phone.currentStep:null;
 const completed=live?phone.stepsCompleted||[]:model.selected?steps.map(s=>s[0]):[];
 const root=$("pipeline-steps");root.replaceChildren();
 steps.forEach(([key,label,sub],index)=>{
  const skip=key==="ai"&&(live?phone.aiEnabled===false:model.selected?.aiApplied===false);
  const active=current===key&&phone?.phase!=="preview"&&phone?.phase!=="transferring";
  const done=completed.includes(key);
  const row=el("div","pipeline-step"+(active?" running":done?" complete":""));
  const node=el("span","step-node");if(done&&!active)node.append(icon("check"));else node.append(el("span","step-number",String(index+1)));
  const copy=el("div","step-copy");const info=el("div");info.append(el("strong","",label),el("p","",skip?"Skipped":active?(key==="ai"?phone.progress:sub):done?"Complete":sub));copy.append(info,el("span","",skip?"—":duration(timings[key])));
  row.append(node,copy);root.append(row);
 });
 $("pipeline-subtitle").textContent=phone?.progress||state.message||"Waiting for the phone";
 $("pipeline-note").textContent=phone?.phase==="preview"?"Preview ready on your phone. Nothing is archived until you press OK.":phone?.phase==="transferring"?"Receiving the approved session. The phone clears its temporary files only after verification.":"Progress arrives over USB. Photo files stay on the phone until you approve the capture.";
}
function renderTransfer(state) {
 const activeSession=state.phone?.sessionId;
 const transfer=activeSession && state.transfer?.sessionId && state.transfer.sessionId!==activeSession ? null : state.transfer; const waiting=state.ready&&state.phone?.phase==="preview"&&!state.phone?.transferRequest;
 const visible=Boolean(transfer)||waiting;show($("transfer-banner"),visible);if(!visible)return;
 const phase=waiting && (!transfer || transfer.sessionId !== state.phone?.sessionId)?"approval":transfer?.phase;
 $("transfer-banner").classList.toggle("failure",phase==="failed");
 $("transfer-title").textContent={approval:"Waiting for your approval",receiving:"Receiving approved capture",verifying:"Verifying every file",complete:"All stages safely archived",failed:"Transfer was not completed"}[phase]||"USB transfer";
 $("transfer-detail").textContent=phase==="approval"?"Review the slider on your phone, then press OK.":phase==="failed"?transfer.error:phase==="complete"?"Checksums verified. The original stage files are in your laptop archive.":bytes(transfer.received||0)+" received · "+bytes(transfer.expected)+" image data";
 $("transfer-progress").value=transfer?.progress||0;show($("transfer-progress"),phase!=="approval");
}
function renderActivity(events) {
 const root=$("activity-list");root.replaceChildren();
 if(!events.length){root.append(el("div","activity-empty","Your next capture starts here."));return;}
 [...events].reverse().slice(0,7).forEach(event=>{const row=el("div","activity-item "+event.kind);row.append(el("span","small-dot blue"));const copy=el("div");copy.append(el("strong","",event.label),el("small","",stamp(event.at)));row.append(copy);root.append(row);});
}
function selectSession(id) {
 const session=model.sessions.find(s=>s.id===id);if(!session)return;
 model.selected=session;model.stage="result.png";model.mode="compare";model.zoom="fit";
 show($("inspector-empty"),false);show($("inspector-content"),true);show($("verified-badge"),true);
 $("inspector-subtitle").textContent=stamp(session.capturedAt)+(session.synthetic?" · Synthetic verification fixture":" · "+session.device);
 $("session-caption").textContent=session.sourceId;
 $("detail-device").textContent=session.device;
 $("detail-mode").textContent=session.synthetic?"Synthetic fixture":session.captureMode;
 $("detail-model").textContent=session.aiApplied?(session.model||"Imported model"):"OpenCV only";
 $("detail-gap").textContent=session.exposureGapMs==null?"—":number(session.exposureGapMs,1)+" ms";
 $("detail-integrity").textContent=session.fileCount+" files · SHA-256 verified";
 $("detail-integrity").className="green-text";
 $("session-notes").disabled=false;$("session-notes").value=session.notes||"";$("save-notes").disabled=false;$("add-reference").disabled=false;
 $("download-session").href=apiPath(id,"/export.zip");$("download-metrics").href=apiPath(id,"/metrics.csv");show($("download-metrics"),true);
 const stages=$("stage-strip");stages.replaceChildren();
 session.files.forEach(stage=>{const button=el("button","stage-button");button.dataset.stage=stage.file;button.title=stage.label;const img=el("img");img.src=apiPath(id,"/image/"+encodeURIComponent(stage.file));img.loading="lazy";img.alt=stage.label;button.append(img,el("strong","",stage.label),el("small","",stage.width+" × "+stage.height));button.addEventListener("click",()=>{model.stage=stage.file;renderImage();});stages.append(button);});
 renderImage();renderMetrics();renderPipeline(model.state||{});
}
function renderImage() {
 if(!model.selected)return;
 const selected=model.selected.files.find(s=>s.file===model.stage)||model.selected.files[0];
 const base=model.selected.files.find(s=>s.file===$("base-stage").value)||selected;
 const matching=selected.width===base.width&&selected.height===base.height;
 const mode=matching?model.mode:"single";
 document.querySelectorAll("[data-mode]").forEach(b=>b.classList.toggle("selected",b.dataset.mode===mode));
 document.querySelectorAll("[data-zoom]").forEach(b=>b.classList.toggle("selected",b.dataset.zoom===model.zoom));
 document.querySelectorAll("[data-stage]").forEach(b=>b.classList.toggle("active",b.dataset.stage===selected.file));
 $("before-image").src=imagePath(base.file);
 $("after-image").src=mode==="difference"?apiPath(model.selected.id,"/difference?left="+encodeURIComponent(base.file)+"&right="+encodeURIComponent(selected.file)):imagePath(selected.file);
 $("after-image").alt=mode==="difference"?"Absolute pixel-difference heatmap":selected.label;
 show($("before-image"),mode==="compare");show($("comparison-line"),mode==="compare");show($("slider-row"),mode==="compare");
 $("before-label").textContent=mode==="difference"?"PIXEL DIFFERENCE":selected.label.toUpperCase();
 $("after-label").textContent=mode==="compare"?base.label.toUpperCase():"";
 $("image-dimensions").textContent=selected.width+" × "+selected.height+" px";
 $("viewer-note").textContent=!matching?"This stage has different dimensions; it is shown on its own.":mode==="difference"?"Brighter areas changed more. This is not an improvement heatmap.":"Pixel differences show what changed, not whether image quality improved.";
 const canvas=$("image-canvas");const fit=model.zoom==="fit";$("image-viewer").classList.toggle("pixel-view",!fit);
 canvas.style.width=fit?"100%":selected.width*Number(model.zoom)+"px";canvas.style.height=fit?"100%":selected.height*Number(model.zoom)+"px";
 $("download-image").href=imagePath(selected.file);$("download-image").download=selected.file;
 if(mode==="compare")updateSlider();else $("after-image").style.clipPath="none";
}
function updateSlider(){if(model.mode!=="compare"||!model.selected)return;const value=$("comparison-slider").value;$("after-image").style.clipPath="inset(0 "+(100-value)+"% 0 0)";$("comparison-line").style.left=value+"%";}
function renderMetrics() {
 const selected=model.selected;if(!selected)return;
 const rows=selected.quality?.rows||[];
 const quality=new Map(rows.map(r=>[r.stage,r]));
 const root=$("metrics-body");root.replaceChildren();
 selected.files.filter(s=>s.file!=="confidence.png").forEach(stage=>{
  const measure=quality.get(stage.file);const delta=selected.changes?.[stage.file]||measure?.change;
  const row=el("tr");[stage.label,stage.width+" × "+stage.height,psnr(measure),number(measure?.ssim,4),delta?number(delta.meanAbsoluteDelta,2)+"/255":"—"].forEach(value=>row.append(el("td","",value)));root.append(row);
 });
 const notice=$("quality-notice");notice.classList.toggle("complete",Boolean(selected.quality));
 notice.querySelector("strong").textContent=selected.quality?"Measured against your aligned reference.":"Quality needs a reference.";
 notice.querySelector("p").textContent=selected.quality?"RGB PSNR and Gaussian SSIM · matching dimensions · no automatic image alignment. Pixel change is reported separately.":"PSNR and SSIM remain unavailable until you add a clean, aligned ground-truth image. Coverage and changed pixels measure activity.";
}
function renderLibrary() {
 const query=$("session-search").value.trim().toLowerCase();const filter=$("session-filter").value;
 const root=$("session-grid");root.replaceChildren();
 const selected=model.sessions.filter(s=>(filter==="all"||filter==="ai"&&s.aiApplied||filter==="baseline"&&!s.aiApplied||filter==="fixture"&&s.synthetic)&&[s.sourceId,s.device,s.model,s.notes,stamp(s.capturedAt)].join(" ").toLowerCase().includes(query));
 if(!selected.length){const empty=el("div","library-empty");empty.append(icon("image"),el("h3","",model.sessions.length?"No matching sessions.":"Your archive starts with an OK."),el("p","",model.sessions.length?"Try a different filter or search.":"Review a capture on your phone and approve it while the laptop companion is ready."));root.append(empty);return;}
 selected.forEach(session=>{const tile=el("button","session-tile");const img=el("img");img.src=apiPath(session.id,"/thumbnail");img.alt=session.synthetic?"Synthetic verification chart":"Archived final image";img.loading="lazy";const body=el("div","session-tile-body");body.append(el("h3","",stamp(session.capturedAt)),el("p","",session.device+" · "+session.fileCount+" stages and diagnostics"));const foot=el("div","session-tile-footer");foot.append(el("span","tag",session.synthetic?"Verification fixture":session.aiApplied?"AI enhanced":"OpenCV baseline"),el("span","",bytes(session.bytes)));body.append(foot);tile.append(img,body);tile.addEventListener("click",()=>{selectSession(session.id);location.hash="workspace";window.scrollTo({top:0,behavior:"smooth"});});root.append(tile);});
}
function renderMatrix() {
 const root=$("matrix-body");root.replaceChildren();
 if(!model.sessions.length){const tr=el("tr");const td=el("td","table-empty","Approve a capture to begin. Ground-truth scores appear only after analysis.");td.colSpan=7;tr.append(td);root.append(tr);return;}
 model.sessions.forEach(session=>{
  const results=session.quality?.rows||[];const original=results.find(r=>r.stage==="comparison-original.png");const final=results.find(r=>r.stage==="result.png");
  const delta=original?.psnrDb!=null&&final?.psnrDb!=null?(final.psnrDb-original.psnrDb).toFixed(2)+" dB":"—";
  const tr=el("tr");const cell=el("td");const link=el("button","matrix-link",stamp(session.capturedAt)+(session.synthetic?" · QA":""));link.onclick=()=>{selectSession(session.id);location.hash="workspace";};cell.append(link);tr.append(cell);
  [session.model||"OpenCV",psnr(original),psnr(final),delta,number(final?.ssim,4),duration(session.processingMs)].forEach(value=>tr.append(el("td","",value)));root.append(tr);
 });
}
$("comparison-slider").addEventListener("input",updateSlider);$("base-stage").addEventListener("change",renderImage);
document.querySelectorAll("[data-mode]").forEach(button=>button.onclick=()=>{model.mode=button.dataset.mode;renderImage();});
document.querySelectorAll("[data-zoom]").forEach(button=>button.onclick=()=>{model.zoom=button.dataset.zoom;renderImage();});
$("session-search").addEventListener("input",renderLibrary);$("session-filter").addEventListener("change",renderLibrary);
$("save-notes").onclick=async()=>{try{await jsonPost(apiPath(model.selected.id,"/notes"),{notes:$("session-notes").value});toast("Experiment notes saved.");}catch(error){toast(error.message,true);}};
$("archive-enabled").onchange=async(event)=>{try{await jsonPost("/api/settings",{paused:!event.target.checked});}catch(error){toast(error.message,true);}};
$("device-select").onchange=async(event)=>{try{await jsonPost("/api/settings",{serial:event.target.value});}catch(error){toast(error.message,true);}};
$("add-reference").onclick=()=>{ $("reference-error").textContent="";$("reference-form").reset();$("reference-file-label").textContent="Choose ground-truth image";$("reference-dialog").showModal();};
$("cancel-reference").onclick=()=>$("reference-dialog").close();
$("reference-file").onchange=()=>{$("reference-file-label").textContent=$("reference-file").files[0]?.name||"Choose ground-truth image";};
$("reference-form").onsubmit=async(event)=>{
 event.preventDefault();const file=$("reference-file").files[0];if(!file||!$("reference-confirm").checked)return;
 try{await request(apiPath(model.selected.id,"/ground-truth?registered=true"),{method:"POST",headers:{"Content-Type":file.type||"application/octet-stream"},body:file});$("reference-dialog").close();toast("Ground-truth analysis started.");}catch(error){$("reference-error").textContent=error.message;}
};
$("export-matrix").onclick=()=>{
 const rows=[["session","device","model","original_psnr_db","final_psnr_db","final_ssim","reference_sha256"]];
 model.sessions.forEach(s=>{const data=s.quality?.rows||[];const a=data.find(r=>r.stage==="comparison-original.png");const b=data.find(r=>r.stage==="result.png");rows.push([s.sourceId,s.device,s.model||"OpenCV",a?.perfectMatch?"inf":a?.psnrDb??"",b?.perfectMatch?"inf":b?.psnrDb??"",b?.ssim??"",s.quality?.referenceSha256||""]);});
 const csv=rows.map(row=>row.map(value=>'"'+String(value).replace(/^[=+@-]/, "'$&").replace(/"/g,'""')+'"').join(",")).join("\r\n");const url=URL.createObjectURL(new Blob([csv],{type:"text/csv"}));const link=el("a");link.href=url;link.download="camera-lab-quality-matrix.csv";link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
navigate();renderLibrary();renderMatrix();renderPipeline({});
request("/api/state").then(renderState).catch(error=>toast(error.message,true));
const events=new EventSource("/api/events");
events.addEventListener("state",event=>{try{renderState(JSON.parse(event.data));}catch(error){console.error(error);}});
events.onerror=()=>{$("footer-status").textContent="Companion connection interrupted · reconnecting";};
