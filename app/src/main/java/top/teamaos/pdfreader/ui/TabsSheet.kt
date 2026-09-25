package top.teamaos.pdfreader.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.teamaos.pdfreader.R
import top.teamaos.pdfreader.core.DocumentController
import top.teamaos.pdfreader.core.DocumentTabs
import top.teamaos.pdfreader.databinding.ItemTabBinding
import top.teamaos.pdfreader.databinding.SheetTabsBinding

/**
 * The list of currently open documents, WPS-style: tap one to switch, tap the cross to close it.
 *
 * It slides down out of the header rather than up from the bottom, because that is where the
 * button that opens it lives — a panel that appears somewhere else reads as an unrelated thing.
 *
 * Closing here really does release the document — its native handles, caches and render thread all
 * go — so a long session does not quietly accumulate open files.
 */
object TabsSheet {

    fun show(
        context: Context,
        onSwitch: (DocumentController) -> Unit,
        onClosed: () -> Unit,
        onOpenAnother: () -> Unit,
    ) {
        val binding = SheetTabsBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context, R.style.Theme_RapidPDF_TopSheet)
        dialog.setContentView(binding.root)
        dialog.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setGravity(Gravity.TOP)
        }
        // The panel's own background runs up behind the status bar — it is hanging off the top
        // edge, and a gap there would show the dimmed page through it — so its content is inset
        // rather than the panel.
        ViewCompat.setOnApplyWindowInsetsListener(binding.sheet) { view, insets ->
            view.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top)
            insets
        }
        // Tapping the darkened page below closes it, the same as a sheet would.
        binding.root.setOnClickListener { dialog.dismiss() }
        binding.sheet.setOnClickListener { }

        val adapter = TabsAdapter(
            onSwitch = { controller ->
                dialog.dismiss()
                onSwitch(controller)
            },
            onClose = { controller ->
                DocumentTabs.close(controller)
                onClosed()
                if (DocumentTabs.count == 0) dialog.dismiss()
            },
        )
        binding.tabList.layoutManager = LinearLayoutManager(context)
        binding.tabList.adapter = adapter
        adapter.submit(DocumentTabs.tabs, DocumentTabs.active)

        binding.openAnotherButton.setOnClickListener {
            dialog.dismiss()
            onOpenAnother()
        }
        dialog.show()
    }

    private class TabsAdapter(
        val onSwitch: (DocumentController) -> Unit,
        val onClose: (DocumentController) -> Unit,
    ) : RecyclerView.Adapter<TabsAdapter.Holder>() {

        private var items: List<DocumentController> = emptyList()
        private var active: DocumentController? = null

        class Holder(val binding: ItemTabBinding) : RecyclerView.ViewHolder(binding.root)

        fun submit(tabs: List<DocumentController>, activeTab: DocumentController?) {
            items = tabs.toList()
            active = activeTab
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val controller = items[position]
            val context = holder.itemView.context
            holder.binding.tabTitle.text = controller.displayName
            holder.binding.tabSubtitle.text =
                context.getString(R.string.pages_count, controller.pageCount)
            holder.binding.activeIndicator.visibility =
                if (controller === active) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnClickListener { onSwitch(controller) }
            holder.binding.closeTabButton.setOnClickListener {
                onClose(controller)
                submit(DocumentTabs.tabs, DocumentTabs.active)
            }
        }
    }
}
