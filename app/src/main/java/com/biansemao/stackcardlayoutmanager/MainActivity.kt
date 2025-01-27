package com.biansemao.stackcardlayoutmanager

import android.content.Context
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.LayoutAnimationController
import android.view.animation.RotateAnimation
import android.widget.ImageView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        val secondListRv = findViewById<RecyclerView>(R.id.rv_list_second)

        val tempList = ArrayList<Int>()
        tempList.add(R.drawable.ic_card_1)
        tempList.add(R.drawable.ic_card_2)
        tempList.add(R.drawable.ic_card_3)
        tempList.add(R.drawable.ic_card_4)
        tempList.add(R.drawable.ic_card_5)
        tempList.add(R.drawable.ic_card_6)

        val verticalConfig = StackCardLayoutManager.StackConfig()
        verticalConfig.stackScale = 0.9f
        verticalConfig.stackCount = 3
        verticalConfig.stackPosition = 0
        verticalConfig.space = 0
        verticalConfig.parallex = 1.5f
        verticalConfig.isCycle = false
        verticalConfig.isAutoCycle = false
        verticalConfig.autoCycleTime = 3500
        verticalConfig.direction = StackCardLayoutManager.StackDirection.LEFT
        secondListRv.layoutManager = StackCardLayoutManager(verticalConfig)
        secondListRv.adapter = TestAdapter(this, tempList)


    }

    /**
     * 根据手机的分辨率从 dp 的单位 转成为 px(像素)
     */
    private fun dip2px(context: Context, dpValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    private class TestAdapter(val context: Context, val data: List<Int>) : RecyclerView.Adapter<TestAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_test, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int {
            return data.size
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.imageIv.setImageResource(data[position])
        }

        private class ViewHolder : RecyclerView.ViewHolder {

            var imageIv: ImageView

            constructor(itemView: View) : super(itemView) {
                imageIv = itemView.findViewById(R.id.iv_image)
            }
        }
    }


}
