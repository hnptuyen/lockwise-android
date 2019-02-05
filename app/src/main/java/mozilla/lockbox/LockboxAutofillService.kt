package mozilla.lockbox

import android.annotation.TargetApi
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mozilla.appservices.logins.ServerPassword
import mozilla.lockbox.action.DataStoreAction
import mozilla.lockbox.flux.Dispatcher
import mozilla.lockbox.store.DataStore
import mozilla.lockbox.support.ParsedStructure
import mozilla.lockbox.support.ParsedStructureBuilder

@TargetApi(Build.VERSION_CODES.O)
@ExperimentalCoroutinesApi
class LockboxAutofillService(
    val dataStore: DataStore = DataStore.shared,
    val dispatcher: Dispatcher = Dispatcher.shared
) : AutofillService() {

    private var compositeDisposable = CompositeDisposable()

    override fun onDisconnected() {
        compositeDisposable.clear()
    }

    override fun onConnected() {
        // stupidly unlock every time :D
        dispatcher.dispatch(DataStoreAction.Unlock)
    }

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.last().structure
        val parsedStructure = ParsedStructureBuilder(structure).build()
        val packageName = parsedStructure.packageId ?: structure.activityComponent.packageName
        val webDomain = parsedStructure.webDomain ?: domainFromPackage(packageName)

        if (parsedStructure.passwordId == null && parsedStructure.usernameId == null) {
            callback.onFailure("couldn't find a username or password field")
            return
        }

        if (webDomain == null) {
            callback.onFailure("unexpected package name structure")
            return
        }

        dataStore.list
            .take(1)
            .subscribe { passwords ->
                val possibleValues = passwords.filter {
                    it.hostname.contains(webDomain, true)
                }
                val response = buildFillResponse(possibleValues, parsedStructure)
                if (response == null) {
                    callback.onFailure("no logins found for this domain")
                } else {
                    callback.onSuccess(response)
                }
            }
            .addTo(compositeDisposable)
    }

    private fun domainFromPackage(packageName: String): String? {
        // naively assume that the `y` from `x.y.z`-style package name is the domain
        // untested as we will change this implementation with issue #375
        val domainRegex = Regex("^\\w+\\.(\\w+)\\..+")
        return domainRegex.find(packageName)?.groupValues?.get(1)
    }

    private fun buildFillResponse(
        possibleValues: List<ServerPassword>,
        parsedStructure: ParsedStructure
    ): FillResponse? {
        if (possibleValues.isEmpty()) {
            return null
        }

        val builder = FillResponse.Builder()

        possibleValues
            .map { serverPasswordToDataset(parsedStructure, it) }
            .forEach { builder.addDataset(it) }

        return builder.build()
    }

    private fun serverPasswordToDataset(parsedStructure: ParsedStructure, credential: ServerPassword): Dataset {
        val datasetBuilder = Dataset.Builder()
        val usernamePresentation = RemoteViews(packageName, R.layout.autofill_item)
        val passwordPresentation = RemoteViews(packageName, R.layout.autofill_item)
        usernamePresentation.setTextViewText(R.id.presentationText, credential.username)
        passwordPresentation.setTextViewText(R.id.presentationText, getString(R.string.password_for, credential.username))

        parsedStructure.usernameId?.let {
            datasetBuilder.setValue(it, AutofillValue.forText(credential.username), usernamePresentation)
        }

        parsedStructure.passwordId?.let {
            datasetBuilder.setValue(it, AutofillValue.forText(credential.password), passwordPresentation)
        }

        return datasetBuilder.build()
    }

    // to be implemented in issue #217
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {}
}