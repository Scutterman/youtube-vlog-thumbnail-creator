package uk.co.cgfindies.youtubevogthumbnailcreator

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class ProfileAdapter(context: Context, private val values: Array<Profile>) : ArrayAdapter<Profile>(context, -1, values) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        Log.i("ProfileAdapter", "Getting view")
        val row: TextView

        return if (convertView != null) convertView else {
            Log.i("ProfileAdapter", "Getting layout inflater")
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            Log.i("ProfileAdapter", "Inflating layout")
            row = inflater.inflate(R.layout.listview_item_profile, parent, false) as TextView
            Log.i("ProfileAdapter", "Setting text")
            row.text = values[position].name
            Log.i("ProfileAdapter", "Setting image")
            row.setCompoundDrawables(BitmapDrawable(context.resources, values[position].overlay), null, null, null)
            Log.i("ProfileAdapter", "Returning row")
            row
        }
    }
}
