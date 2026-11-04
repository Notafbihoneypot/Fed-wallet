package app.independentwallet.android

/** Capture before queueing work; the same monitor fences commits and revocation. */
internal class SecurityAuthorization(private val now:()->Long) {
    internal class Grant internal constructor(internal val owner:SecurityAuthorization,internal val epoch:Long,internal val issuedAt:Long)
    private var epoch=0L
    @Synchronized fun issue()=Grant(this,epoch,now())
    @Synchronized fun revoke(){++epoch}
    @Synchronized fun current(grant:Grant):Boolean {
        val time=now()
        return grant.owner===this&&grant.epoch==epoch&&time>=grant.issuedAt&&time-grant.issuedAt<LIFETIME_MS
    }
    @Synchronized fun <T> commit(grant:Grant,work:()->T):T {
        check(current(grant)){"Authentication was revoked or expired"}
        return work()
    }
    companion object {const val LIFETIME_MS=60_000L}
}
