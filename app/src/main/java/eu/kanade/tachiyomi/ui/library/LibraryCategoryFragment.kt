package eu.kanade.tachiyomi.ui.library

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.f2prateek.rx.preferences.Preference
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.event.LibraryMangasEvent
import eu.kanade.tachiyomi.ui.base.adapter.FlexibleViewHolder
import eu.kanade.tachiyomi.ui.base.fragment.BaseFragment
import eu.kanade.tachiyomi.ui.manga.MangaActivity
import kotlinx.android.synthetic.main.fragment_library_category.*
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import rx.Subscription
import java.util.*

/**
 * Fragment containing the library manga for a certain category.
 * Uses R.layout.fragment_library_category.
 */
class LibraryCategoryFragment : BaseFragment(), FlexibleViewHolder.OnListItemClickListener {

    /**
     * Adapter to hold the manga in this category.
     */
    lateinit var adapter: LibraryCategoryAdapter
        private set

    /**
     * Position in the adapter from [LibraryAdapter].
     */
    private var position: Int = 0

    /**
     * Manga in this category.
     */
    private var mangas: List<Manga>? = null
        set(value) {
            field = value ?: ArrayList()
        }

    /**
     * Subscription of the number of manga per row.
     */
    private var numColumnsSubscription: Subscription? = null

    /**
     * Subscription of the library search.
     */
    private var searchSubscription: Subscription? = null

    companion object {
        /**
         * Key to save and restore [position] from a [Bundle].
         */
        const val POSITION_KEY = "position_key"

        /**
         * Creates a new instance of this class.
         *
         * @param position the position in the adapter from [LibraryAdapter].
         * @return a new instance of [LibraryCategoryFragment].
         */
        fun newInstance(position: Int): LibraryCategoryFragment {
            val fragment = LibraryCategoryFragment()
            fragment.position = position
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_library_category, container, false)
    }

    override fun onViewCreated(view: View, savedState: Bundle?) {
        adapter = LibraryCategoryAdapter(this)
        recycler.setHasFixedSize(true)
        recycler.adapter = adapter

        if (libraryFragment.actionMode != null) {
            setSelectionMode(FlexibleAdapter.MODE_MULTI)
        }

        numColumnsSubscription = getColumnsPreferenceForCurrentOrientation().asObservable()
                .doOnNext { recycler.spanCount = it }
                .skip(1)
                // Set again the adapter to recalculate the covers height
                .subscribe { recycler.adapter = adapter }

        searchSubscription = libraryPresenter.searchSubject.subscribe { text ->
            adapter.searchText = text
            adapter.updateDataSet()
        }

        if (savedState != null) {
            position = savedState.getInt(POSITION_KEY)
            adapter.onRestoreInstanceState(savedState)

            if (adapter.mode == FlexibleAdapter.MODE_SINGLE) {
                adapter.clearSelection()
            }
        }
    }

    override fun onDestroyView() {
        numColumnsSubscription?.unsubscribe()
        searchSubscription?.unsubscribe()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        registerForEvents()
    }

    override fun onPause() {
        unregisterForEvents()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(POSITION_KEY, position)
        adapter.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    /**
     * Subscribe to [LibraryMangasEvent]. When an event is received, it updates [mangas] if needed
     * and refresh the content of the adapter.
     *
     * @param event the event received.
     */
    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    fun onEvent(event: LibraryMangasEvent) {
        // Get the categories from the parent fragment.
        val categories = libraryFragment.adapter.categories ?: return

        // When a category is deleted, the index can be greater than the number of categories.
        if (position >= categories.size) return

        // Get the manga list for this category
        val mangaForCategory = event.getMangasForCategory(categories[position])

        // Update the list only if the reference to the list is different, avoiding reseting the
        // adapter after every onResume.
        if (mangas !== mangaForCategory) {
            mangas = mangaForCategory
            mangas?.let { adapter.setItems(it) }
        }
    }

    /**
     * Called when a manga is clicked.
     *
     * @param position the position of the element clicked.
     * @return true if the item should be selected, false otherwise.
     */
    override fun onListItemClick(position: Int): Boolean {
        // If the action mode is created and the position is valid, toggle the selection.
        val item = adapter.getItem(position) ?: return false
        if (libraryFragment.actionMode != null) {
            toggleSelection(position)
            return true
        } else {
            openManga(item)
            return false
        }
    }

    /**
     * Called when a manga is long clicked.
     *
     * @param position the position of the element clicked.
     */
    override fun onListItemLongClick(position: Int) {
        libraryFragment.createActionModeIfNeeded()
        toggleSelection(position)
    }

    /**
     * Opens a manga.
     *
     * @param manga the manga to open.
     */
    protected fun openManga(manga: Manga) {
        // Notify the presenter a manga is being opened.
        libraryPresenter.onOpenManga()

        // Create a new activity with the manga.
        val intent = MangaActivity.newIntent(activity, manga)
        startActivity(intent)
    }

    /**
     * Toggles the selection for a manga.
     *
     * @param position the position to toggle.
     */
    private fun toggleSelection(position: Int) {
        val library = libraryFragment

        // Toggle the selection.
        adapter.toggleSelection(position, false)

        // Notify the selection to the presenter.
        library.presenter.setSelection(adapter.getItem(position), adapter.isSelected(position))

        // Get the selected count.
        val count = library.presenter.selectedMangas.size
        if (count == 0) {
            // Destroy action mode if there are no items selected.
            library.destroyActionModeIfNeeded()
        } else {
            // Update action mode with the new selection.
            library.setContextTitle(count)
            library.setVisibilityOfCoverEdit(count)
            library.invalidateActionMode()
        }
    }

    /**
     * Returns a preference for the number of manga per row based on the current orientation.
     *
     * @return the preference.
     */
    fun getColumnsPreferenceForCurrentOrientation(): Preference<Int> {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
            libraryPresenter.preferences.portraitColumns()
        else
            libraryPresenter.preferences.landscapeColumns()
    }

    /**
     * Sets the mode for the adapter.
     *
     * @param mode the mode to set. It should be MODE_SINGLE or MODE_MULTI.
     */
    fun setSelectionMode(mode: Int) {
        adapter.mode = mode
        if (mode == FlexibleAdapter.MODE_SINGLE) {
            adapter.clearSelection()
        }
    }

    /**
     * Property to get the library fragment.
     */
    private val libraryFragment: LibraryFragment
        get() = parentFragment as LibraryFragment

    /**
     * Property to get the library presenter.
     */
    private val libraryPresenter: LibraryPresenter
        get() = libraryFragment.presenter

}
