package uk.co.cgfindies.youtubevogthumbnailcreator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class ProfileAdapter(context: Context, private val values: MutableList<Profile>) : ArrayAdapter<Profile>(context, -1, values) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row: TextView

        return if (convertView != null) {
            convertView
        } else {
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            row = inflater.inflate(R.layout.listview_item_profile, parent, false) as TextView
            row.text = values[position].name
            val preview = BitmapDrawable(context.resources, Bitmap.createScaledBitmap(values[position].overlay, 111, 100, false))
            preview.bounds = Rect(0, 0, 111, 100)
            val existingDrawables = row.compoundDrawables
            row.setCompoundDrawables(preview, existingDrawables[1], existingDrawables[2], existingDrawables[3])
            row
        }
    }
}
