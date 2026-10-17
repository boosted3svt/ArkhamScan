package com.arkhamscan

import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RemoteTestApplication::class)
class RemoteSessionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: ScanViewModel
    @Before fun ready() {
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[ScanViewModel::class.java] }
        waitFor { vm.databaseReady.value && vm.cards.value.size == 2 }
    }
    private fun waitFor(f: () -> Boolean) = compose.waitUntil(15_000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); f() }
    private fun <T> suspendCall(f: suspend () -> T): T {
        lateinit var d: Deferred<T>
        compose.runOnIdle { d = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).async { f() } }
        waitFor { d.isCompleted }
        return runBlocking { d.await() }
    }
    private fun body(action: String) = JSONObject().put("id", UUID.randomUUID().toString()).put("action", action)
        .put("revision", suspendCall { vm.store.currentBulkInventory()!!.revision }).put("presentation", vm.remote.presentation)
    private fun send(b: JSONObject) = suspendCall { vm.remote.command(b) }
    private fun select() { send(body("select").put("card", "test:a")) }
    private fun confirm(): JSONObject { select(); return body("confirm").put("card", "test:a").also { send(it) } }
    @After fun close() { compose.runOnIdle { compose.activity.viewModelStore.clear() }; (compose.activity.application as ScanApplication).database.close() }

    @Test fun lostAcknowledgmentReplaySurvivesNewCoordinatorAndNeverCreatesAnotherCopy() {
        val b = confirm()
        assertEquals(1, suspendCall { vm.store.quantity("test:a") })
        assertTrue(send(b).getBoolean("replayed"))
        val freshCoordinator = RemoteSession(vm, CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate))
        assertTrue(suspendCall { freshCoordinator.command(b) }.getBoolean("replayed"))
        assertEquals(1, suspendCall { vm.store.quantity("test:a") })
        assertNotNull(suspendCall { vm.store.remoteCommand(b.getString("id")) })
        try { send(JSONObject(b.toString()).put("card", "test:b")); fail() } catch (_: IllegalArgumentException) {}
        try { send(JSONObject(b.toString()).put("id", UUID.randomUUID().toString())); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(1, suspendCall { vm.store.quantity("test:a") })
    }
    @Test fun concurrentNativeCorrectionRejectsStaleBrowserMutationAndLeavesNoAcknowledgment() {
        select(); val b=body("confirm").put("card","test:a")
        suspendCall { vm.adjustQuantityNow(RemoteTestApplication.a,1,"native-correction") }
        try { send(b); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(1,suspendCall { vm.store.quantity("test:a") })
        assertNull(suspendCall { vm.store.remoteCommand(b.getString("id")) })
    }
    @Test fun reSortBlocksInventoryCorrectionsAndRemoteRoutesPreserveOwnership() {
        confirm(); waitFor { vm.copies.value.size==1 }
        suspendCall { vm.makePlanNow() }; waitFor { vm.passes.value.any { it.status=="active" } }
        for (b in listOf(body("adjust").put("card","test:a").put("delta",1), body("undo").put("copy",vm.copies.value.first().id))) {
            try { send(b); fail() } catch (_: IllegalArgumentException) {}
        }
        select(); val route=body("route").put("card","test:a");send(route)
        assertTrue(vm.lastPile.value!!.contains("Pile 1"))
        assertTrue(send(route).getBoolean("replayed"))
        assertEquals(1,suspendCall { vm.store.quantity("test:a") })
    }
    @Test fun referenceReplacementKeepsHistoricalCardsVisibleAndEditableInBrowserCollection() {
        confirm()
        suspendCall { vm.store.importCards(listOf(RemoteTestApplication.a.copy(id="test:b",title="Another Card"))) }
        waitFor { vm.cards.value.size==1 }
        val page=suspendCall { vm.remote.page("collection","",0) }
        assertEquals("test:a",page.getJSONArray("items").getJSONObject(0).getString("id"))
        send(body("adjust").put("card","test:a").put("delta",-1))
        assertEquals(0,suspendCall { vm.store.quantity("test:a") })
        send(body("adjust").put("card","test:a").put("delta",1))
        assertEquals(1,suspendCall { vm.store.quantity("test:a") })
    }
    @Test fun cameraBlockersAndPauseStopDoNotChangeInventoryOrHeldPresentation() {
        val p=vm.remote.presentation
        compose.runOnIdle { vm.remote.setForeground(false) }
        try { send(body("camera").put("value","start")); fail() } catch (e:IllegalArgumentException) { assertTrue(e.message!!.contains("unlock")) }
        compose.runOnIdle { vm.remote.setForeground(true) }
        try { send(body("camera").put("value","start")); fail() } catch(e:IllegalArgumentException) { assertTrue(e.message!!.contains("permission")) }
        send(body("camera").put("value","pause"));assertEquals("paused",vm.remote.cameraStatus.value)
        send(body("camera").put("value","stop"));assertEquals("stopped",vm.remote.cameraStatus.value)
        assertEquals(p,vm.remote.presentation);assertEquals(0,suspendCall { vm.store.quantity("test:a") })
        assertTrue(vm.remote.diagnostics().getJSONArray("errors").length()>0)
    }
    private fun wire(port:Int, path:String, method:String="GET", data:JSONObject?=null, cookie:String="", origin:String?="http://127.0.0.1:$port", host:String="127.0.0.1:$port", event:Boolean=false): String {
        val pool=Executors.newSingleThreadExecutor()
        val future=pool.submit(Callable {
            Socket("127.0.0.1",port).use { socket ->
                socket.soTimeout=10_000
                val bytes=data?.toString()?.toByteArray() ?: ByteArray(0)
                val head=buildString {
                    append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
                    if(event)append("Accept-Encoding: gzip\r\n")
                    if(origin!=null)append("Origin: $origin\r\n")
                    if(cookie.isNotBlank())append("Cookie: $cookie\r\n")
                    if(data!=null)append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                    append("\r\n")
                }
                socket.getOutputStream().write(head.toByteArray()+bytes)
                if(event) {
                    val input=socket.getInputStream();val out=StringBuilder()
                    while(out.length<100_000) { val n=input.read();if(n<0)break;out.append(n.toChar());if(out.contains("data:")&&out.endsWith("\n\n"))break };out.toString()
                } else socket.getInputStream().bufferedReader().readText()
            }
        })
        try { waitFor { future.isDone };return future.get() } finally { pool.shutdownNow() }
    }
    @Test fun actualHttpListenerRequiresPairingOriginAndLiteralHostAndStreamsAuthoritativeState() {
        val server=RemoteHttpServer(compose.activity.assets,vm.remote,0);server.startServer()
        try {
            val port=server.listeningPort
            assertTrue(wire(port,"/").contains("Your phone scans."))
            assertTrue(wire(port,"/api/state").startsWith("HTTP/1.1 401"))
            assertTrue(wire(port,"/api/pair","POST",JSONObject().put("code",server.pairCode),origin="http://attacker.example").startsWith("HTTP/1.1 403"))
            assertTrue(wire(port,"/api/state",host="attacker.example:$port").startsWith("HTTP/1.1 403"))
            val paired=wire(port,"/api/pair","POST",JSONObject().put("code",server.pairCode))
            assertTrue(paired.startsWith("HTTP/1.1 200"))
            val cookie=Regex("(?i)set-cookie: ([^;]+)").find(paired)!!.groupValues[1]
            assertTrue(wire(port,"/api/state",cookie=cookie).contains("\"totalCopies\":0"))
            assertTrue(wire(port,"/api/events",cookie=cookie,event=true).contains("event: state"))
            assertTrue(wire(port,"/api/pair","POST",JSONObject().put("code",server.pairCode)).startsWith("HTTP/1.1 409"))
            val selection=body("select").put("card","test:a")
            assertTrue(wire(port,"/api/command","POST",selection,cookie).contains("\"ok\":true"))
            val b=body("confirm").put("card","test:a")
            assertTrue(wire(port,"/api/command","POST",b,cookie).contains("Pile 1"))
            assertTrue(wire(port,"/api/command","POST",b,cookie).contains("\"replayed\":true"))
            assertEquals(1,suspendCall { vm.store.quantity("test:a") })
            val oldPairCode=server.pairCode
            server.revoke();assertNotEquals(oldPairCode,server.pairCode);assertTrue(wire(port,"/api/state",cookie=cookie).startsWith("HTTP/1.1 401"))
        } finally { server.stop() }
    }
}
class RemoteTestApplication:ScanApplication() {
    override val database:ScanDatabase by lazy { Room.inMemoryDatabaseBuilder(this,ScanDatabase::class.java).build() }
    override fun onCreate() { super.onCreate();runBlocking { database.store().importCards(listOf(a,a.copy(id="test:b",title="Another Card",number="2"))) } }
    companion object { val a=CatalogCard("test:a","Synthetic Card","1","p","Test box","asset","","fixture","v1",expectedQuantity=2) }
}
