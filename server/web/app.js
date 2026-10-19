'use strict';
const $=id=>document.getElementById(id);
let session=null;
function busy(value){for(const button of document.querySelectorAll('button'))button.disabled=value;}
function message(text){$('message').textContent=text;}
async function post(path,body,token){
 const response=await fetch(path,{method:'POST',credentials:'omit',cache:'no-store',redirect:'error',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body)});
 const result=await response.json();
 if(!response.ok)throw new Error(result.error?.message || '요청을 완료하지 못했습니다. 다시 시도해 주세요.');
 return result;
}
$('login').addEventListener('submit',async event=>{
 event.preventDefault();busy(true);message('계정을 확인하고 있습니다.');
 const password=$('password').value;$('password').value='';
 try {
  session=await post('/auth/login',{username:$('username').value.trim(),password});
  $('account').textContent=session.user.username+' 계정';$('login').hidden=true;$('confirm').hidden=false;
  message('삭제 범위를 확인한 후 비밀번호를 다시 입력해 주세요.');$('confirmation').focus();
 }catch(error){message(error.message);}finally{busy(false);}
});
$('erase').addEventListener('submit',async event=>{
 event.preventDefault();if(!session || !$('consent').checked)return;
 busy(true);message('삭제 결과를 확인하고 있습니다.');
 const password=$('confirmation').value;$('confirmation').value='';
 try {
  await post('/auth/delete',{username:session.user.username,password,confirm: true},session.token);
  session=null;$('confirm').hidden=true;$('success').hidden=false;message('삭제 완료');
 }catch(error){message(error.message+' 응답을 잃었다면 같은 페이지에서 비밀번호를 입력해 다시 요청해 주세요.');}
 finally{busy(false);}
});
$('cancel').addEventListener('click',async()=>{
 busy(true);
 try {if(session)await post('/auth/logout',{},session.token);}
 catch{message('로그아웃 응답을 확인하지 못했습니다. 이 페이지의 로그인 정보는 비웁니다.');}
 finally{session=null;message('삭제를 취소했습니다.');$('confirmation').value='';$('consent').checked=false;$('confirm').hidden=true;$('login').hidden=false;busy(false);}
});
// Session exists only in this page's memory. Nothing is written to browser storage.
