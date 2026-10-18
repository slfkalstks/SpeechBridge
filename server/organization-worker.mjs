import { ApiError } from './errors.mjs';

/** One local inference at a time; PostgreSQL owns job state and crash recovery. */
export function createOrganizationWorker({jobs,organize,acquire,intervalMs=1000,deadlineMs=95_000}) {
  let timer, running, controller, stopped=false, cursor='';
  async function execute() {
    const release=acquire(); if(!release) return;
    let job, deadline;
    controller=new AbortController();
    try {
      const users=await jobs.usersAfter(cursor);
      if(!users.length) cursor='';
      for(const user of users) {
        if(stopped) return;
        cursor=user;
        job=await jobs.claim(user);
        if(job) break;
      }
      if(!job) return;
      if(stopped) { await jobs.release(job); return; }
      deadline=setTimeout(()=>controller.abort(new ApiError(504,'model_timeout','AI 처리 시간이 지났습니다.')),deadlineMs);
      try {
        const result=await organize(job.input,{signal:controller.signal});
        controller.signal.throwIfAborted();
        await jobs.complete(job,result);
      } catch(error) {
        if(stopped) await jobs.release(job);
        else await jobs.fail(job,controller.signal.aborted?controller.signal.reason:error);
      }
    } finally {
      clearTimeout(deadline);controller=null;release();
    }
  }
  function runOnce() {
    if(stopped) return Promise.resolve();
    if(!running) running=execute().finally(()=>{running=null;});
    return running;
  }
  return {
    runOnce,
    start() {
      if(timer || stopped) return;
      // DB failures leave the persisted lease recoverable. Never log raw provider/DB errors.
      const tick=()=>void runOnce().catch(()=>{});
      timer=setInterval(tick,intervalMs);timer.unref();tick();
    },
    async stop() {
      stopped=true;clearInterval(timer);controller?.abort();
      await running?.catch(()=>{});
    },
  };
}
