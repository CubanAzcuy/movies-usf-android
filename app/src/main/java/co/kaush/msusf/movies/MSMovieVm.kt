package co.kaush.msusf.movies

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import co.kaush.msusf.MSApp
import co.kaush.msusf.movies.MSMovieEvent.AddToHistoryEvent
import co.kaush.msusf.movies.MSMovieEvent.RestoreFromHistoryEvent
import co.kaush.msusf.movies.MSMovieEvent.ScreenLoadEvent
import co.kaush.msusf.movies.MSMovieEvent.SearchMovieEvent
import co.kaush.msusf.movies.MSMovieResult.AddToHistoryResult
import co.kaush.msusf.movies.MSMovieResult.ScreenLoadResult
import co.kaush.msusf.movies.MSMovieResult.SearchMovieResult
import co.kaush.msusf.movies.MSMovieViewEffect.AddedToHistoryToastEffect
import com.jakewharton.rx.replayingShare
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import timber.log.Timber

/**
 * For this example, a simple ViewModel would have sufficed,
 * but in most real world examples we would use an AndroidViewModel
 *
 * Our Unit tests should still be able to run given this
 */
class MSMainVm(
    app: MSApp,
    private val movieRepo: MSMovieRepository
) : AndroidViewModel(app) {

    private val eventEmitter: PublishSubject<MSMovieEvent> = PublishSubject.create()

    private lateinit var disposable: Disposable


    /**
     * It is unnecessary to do `subscribeOn(Schedulers.io())` as
     * the chain executes on the thread that pushed an "event" into [processInput].
     *
     * **Example:**
     * ```
     * vm.viewState
     *  .observeOn(AndroidSchedulers.mainThread())
     *  .subscribe(::render, Timber::w)
     * ```
     */
    val viewState: Observable<MSMovieViewState>
    val viewEffects: Observable<MSMovieViewEffect>

    init {
        Timber.d("------ init ${Thread.currentThread().name}")
        eventEmitter
            .doOnNext { Timber.d("----- event (init) ${Thread.currentThread().name} $it ") }
            .eventToResult()
            .doOnNext { Timber.d("----- result ${Thread.currentThread().name} $it") }
            // share the result stream otherwise it will get subscribed to multiple times
            // in the following also block
            .share()
            .also { result ->
                // Timber.d("------ also ${Thread.currentThread().name}")
                viewState = result
                    .resultToViewState()
                     // if the viewState is identical there's little reason to remit
                    .distinctUntilChanged()
                    .doOnNext { Timber.d("----- vs $it") }

                    // when a View rebinds to the ViewModel after rotation/config change
                    // emit the last viewState of the stream on subscription
                    .replay(1)

                    // autoConnect makes sure the streams stays alive even when the UI disconnects
                    // autoConnect(0) kicks off the stream without waiting for anyone to subscribe
                    .autoConnect(0) {disposable = it }

                viewEffects = result
                    .resultToViewEffect()
                    .doOnNext { Timber.d("----- ve $it") }
            }
    }

    override fun onCleared() {
        super.onCleared()
        disposable.dispose()
    }

    fun processInput(event: MSMovieEvent) {
        Timber.d("----- event (processInput) [${eventEmitter.hasObservers()}] ${Thread.currentThread().name} $event ")
        eventEmitter.onNext(event)
    }

    // -----------------------------------------------------------------------------------
    // Internal helpers

    private fun Observable<MSMovieEvent>.eventToResult(): Observable<Lce<out MSMovieResult>> {
        return publish { o ->
            Observable.merge(
                o.ofType(ScreenLoadEvent::class.java).onScreenLoad(),
                o.ofType(SearchMovieEvent::class.java).onSearchMovie(),
                o.ofType(AddToHistoryEvent::class.java).onAddToHistory(),
                o.ofType(RestoreFromHistoryEvent::class.java).onRestoreFromHistory()
            )
        }
    }

    private fun Observable<Lce<out MSMovieResult>>.resultToViewState(): Observable<MSMovieViewState> {
        return scan(MSMovieViewState()) { vs, result ->
            when (result) {
                is Lce.Content -> {
                    when (result.packet) {
                        is ScreenLoadResult -> {
                            vs.copy(searchBoxText = "")
                        }
                        is SearchMovieResult -> {
                            val movie: MSMovie = result.packet.movie
                            vs.copy(
                                searchedMovieTitle = movie.title,
                                searchedMovieRating = movie.ratingSummary,
                                searchedMoviePoster = movie.posterUrl,
                                searchedMovieReference = movie
                            )
                        }

                        is AddToHistoryResult -> {
                            val movieToBeAdded: MSMovie = result.packet.movie

                            if (!vs.adapterList.contains(movieToBeAdded)) {
                                vs.copy(adapterList = vs.adapterList.plus(movieToBeAdded))
                            } else vs.copy()
                        }
                    }
                }

                is Lce.Loading -> {
                    vs.copy(
                        searchBoxText = null,
                        searchedMovieTitle = "Searching Movie...",
                        searchedMovieRating = "",
                        searchedMoviePoster = "",
                        searchedMovieReference = null
                    )
                }

                is Lce.Error -> {
                    when (result.packet) {
                        is SearchMovieResult -> {
                            val movie: MSMovie = result.packet.movie
                            vs.copy(searchedMovieTitle = movie.errorMessage!!)
                        }
                        else -> throw RuntimeException("Unexpected result LCE state")
                    }
                }
            }
        }
    }

    private fun Observable<Lce<out MSMovieResult>>.resultToViewEffect(): Observable<MSMovieViewEffect> {
        return filter { it is Lce.Content && it.packet is AddToHistoryResult }
            .map<MSMovieViewEffect> { AddedToHistoryToastEffect }
    }

    // -----------------------------------------------------------------------------------
    // use cases
    private fun Observable<ScreenLoadEvent>.onScreenLoad(): Observable<Lce<ScreenLoadResult>> {
        return map { Lce.Content(ScreenLoadResult) }
    }

    private fun Observable<SearchMovieEvent>.onSearchMovie(): Observable<Lce<SearchMovieResult>> {
        return switchMap { searchMovieEvent ->
            movieRepo.searchMovie(searchMovieEvent.searchedMovieTitle)
                .subscribeOn(Schedulers.io())
                .map {
                    if (it.errorMessage?.isNullOrBlank() == false) {
                        Lce.Error(SearchMovieResult(it))
                    } else {
                        Lce.Content(SearchMovieResult(it))
                    }
                }
                .onErrorReturn {
                    Lce.Error(SearchMovieResult(MSMovie(result = false, errorMessage = it.localizedMessage)))
                }
                .startWith(Lce.Loading())
        }
    }

    private fun Observable<AddToHistoryEvent>.onAddToHistory(): Observable<Lce<AddToHistoryResult>> {
        return map { Lce.Content(AddToHistoryResult(it.searchedMovie)) }
    }

    private fun Observable<RestoreFromHistoryEvent>.onRestoreFromHistory(): Observable<Lce<SearchMovieResult>> {
        return map { Lce.Content(SearchMovieResult(it.movieFromHistory)) }
    }

// -----------------------------------------------------------------------------------
// LCE

    sealed class Lce<T> {
        class Loading<T> : Lce<T>()
        data class Content<T>(val packet: T) : Lce<T>()
        data class Error<T>(val packet: T) : Lce<T>()
    }

// -----------------------------------------------------------------------------------

    class MSMainVmFactory(
        private val app: MSApp,
        private val movieRepo: MSMovieRepository
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            return MSMainVm(app, movieRepo) as T
        }
    }
}
