package net.duhowpi.ftmsbridge

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import net.duhowpi.ftmsbridge.databinding.ItemScanResultBinding
import net.duhowpi.ftmsbridge.model.ScannedDeviceInfo

class ScanResultAdapter(
    private val onConnect: (ScannedDeviceInfo) -> Unit
) : RecyclerView.Adapter<ScanResultAdapter.ViewHolder>() {

    private val items = mutableListOf<ScannedDeviceInfo>()
    private val connectedAddresses = mutableSetOf<String>()

    class ViewHolder(val binding: ItemScanResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScanResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val isConnected = connectedAddresses.contains(item.address)
        holder.binding.apply {
            txtDeviceType.text = item.typeLabel
            txtDeviceName.text = item.name
            txtDeviceAddress.text = item.address
            txtSignalBars.text = item.signalBars
            txtRssi.text = item.rssiLabel
            btnConnect.text = if (isConnected)
                root.context.getString(R.string.connected_label) else root.context.getString(R.string.connect)
            btnConnect.isEnabled = !isConnected
            btnConnect.setOnClickListener { if (!isConnected) onConnect(item) }
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateAll(newItems: List<ScannedDeviceInfo>) {
        items.clear()
        items.addAll(newItems.sortedWith(compareByDescending<ScannedDeviceInfo> {
            it.isFtms || it.isHr
        }.thenByDescending { it.rssi }))
        notifyDataSetChanged()
    }

    fun markConnected(address: String) {
        connectedAddresses.add(address)
        val idx = items.indexOfFirst { it.address == address }
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun markDisconnected(address: String) {
        connectedAddresses.remove(address)
        val idx = items.indexOfFirst { it.address == address }
        if (idx >= 0) notifyItemChanged(idx)
    }
}
