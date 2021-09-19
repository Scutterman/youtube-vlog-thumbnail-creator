package uk.co.cgfindies.youtubevogthumbnailcreator

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class ProfileAdapter(context: Context, private val values: MutableList<Profile>) : ArrayAdapter<Profile>(context, -1, values) {
    var itemDeletedListener: (position: Int) -> Unit = {}

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return if (convertView != null) convertView else {
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val row = inflater.inflate(R.layout.listview_item_profile, parent, false) as LinearLayout

            row.findViewById<TextView>(R.id.profile_name).text = values[position].name

            val preview = Bitmap.createScaledBitmap(values[position].overlay, 111, 100, false)
            row.findViewById<ImageView>(R.id.overlay_preview).setImageBitmap(preview)

            row.findViewById<ImageView>(R.id.delete_profile).setOnClickListener {
                itemDeletedListener(position)
            }
            row
        }
    }
}
