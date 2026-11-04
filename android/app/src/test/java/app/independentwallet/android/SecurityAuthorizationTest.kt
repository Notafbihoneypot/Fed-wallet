package app.independentwallet.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SecurityAuthorizationTest {
    @Test fun queuedWorkCannotAcquireNewAuthorityAfterLock(){
        var now=10L;val authority=SecurityAuthorization{now};val grant=authority.issue()
        val ready=CountDownLatch(1);val resume=CountDownLatch(1);val executor=Executors.newSingleThreadExecutor()
        try{
            val future=executor.submit<Boolean>{ready.countDown();check(resume.await(5,TimeUnit.SECONDS));try{authority.commit(grant){true}}catch(_:IllegalStateException){false}}
            assertTrue(ready.await(5,TimeUnit.SECONDS));authority.revoke();now=11L;resume.countDown()
            assertFalse(future.get(5,TimeUnit.SECONDS));assertTrue(authority.commit(authority.issue()){true})
        }finally{resume.countDown();executor.shutdownNow()}
    }
    @Test fun grantsExpireRejectClockRollbackAndBelongToOneOwner(){
        var now=200L;val a=SecurityAuthorization{now};val b=SecurityAuthorization{now};val grant=a.issue()
        assertFalse(b.current(grant));now=199;assertFalse(a.current(grant));now=60_199;assertTrue(a.current(grant));now=60_200;assertFalse(a.current(grant))
        assertThrows(IllegalStateException::class.java){a.commit(grant){error("Must never run")}}
    }
    @Test fun revocationWaitsForInFlightProtectedCommitAndRejectsNextCommit(){
        val authority=SecurityAuthorization{100L};val grant=authority.issue()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val revoking=CountDownLatch(1)
        val executor=Executors.newFixedThreadPool(2)
        try{
            val conversion=executor.submit<Boolean>{authority.commit(grant){entered.countDown();check(release.await(5,TimeUnit.SECONDS));true}}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val locked=executor.submit<Boolean>{revoking.countDown();authority.revoke();true}
            assertTrue(revoking.await(5,TimeUnit.SECONDS));assertFalse(locked.isDone)
            release.countDown();assertTrue(conversion.get(5,TimeUnit.SECONDS));assertTrue(locked.get(5,TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java){authority.commit(grant){error("revoked")}}
        }finally{release.countDown();executor.shutdownNow()}
    }
}
