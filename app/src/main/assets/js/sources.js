"use strict";
const $=id=>document.getElementById(id);
let token=new URLSearchParams(location.hash.slice(1)).get("token") || sessionStorage.getItem("sourceToken") || "";
if(location.hash) history.replaceState(null,"",location.pathname);
function message(text,error=false){$("status").textContent=text;$("status").classList.toggle("error",error);}
function connected(value){$("pairPanel").hidden=value;$("connectedPanel").hidden=!value;$("connection").textContent=value?"已连接电视":"未连接";}
async function request(path,fields){
 const ctrl=new AbortController(), timer=setTimeout(()=>ctrl.abort(),12000);
 try{
  const res=await fetch(path,{method:fields?"POST":"GET",headers:token?{"X-Source-Token":token}:{},body:fields?new URLSearchParams(fields):undefined,signal:ctrl.signal,cache:"no-store"});
  const data=await res.json();
  if(!res.ok){if(res.status===401){token="";sessionStorage.removeItem("sourceToken");connected(false);}throw new Error(data.message||"请求失败，请重试");}
  return data;
 }catch(e){if(e.name==="AbortError")throw new Error("连接超时，请确认手机和电视在同一局域网");if(e instanceof TypeError)throw new Error("无法连接电视，请检查网络后重试");throw e;}
 finally{clearTimeout(timer);}
}
async function refresh(){const data=await request("/api/sources");connected(true);sessionStorage.setItem("sourceToken",token);$("sources").replaceChildren();
 for(const item of data.items){const li=document.createElement("li"),name=document.createElement("div");name.textContent=item.name;li.append(name);if(item.active){const badge=document.createElement("span");badge.textContent="✓ 当前线路";li.append(badge);}$("sources").append(li);}
 if(!data.items.length){const li=document.createElement("li");li.textContent="还没有保存线路，先添加一个吧";$("sources").append(li);}}
$("pairForm").addEventListener("submit",async e=>{e.preventDefault();$("login").disabled=true;try{const data=await request("/api/sources/login",{code:$("pairCode").value.trim()});token=data.token;await refresh();message("已连接，可以添加整合源。");}catch(err){message(err.message,true);}finally{$("login").disabled=false;}});
$("sourceForm").addEventListener("submit",async e=>{e.preventDefault();$("save").disabled=true;message("正在识别名称并添加…");try{const data=await request("/api/sources",{url:$("sourceUrl").value.trim()});await refresh();message(data.message);$("sourceUrl").value="";}catch(err){message(err.message,true);}finally{$("save").disabled=false;}});
$("refresh").addEventListener("click",async()=>{try{await refresh();message("线路列表已更新");}catch(err){message(err.message,true);}});
if(token)refresh().catch(err=>message(err.message,true));
