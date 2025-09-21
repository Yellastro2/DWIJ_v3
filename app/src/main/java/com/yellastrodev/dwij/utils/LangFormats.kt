package com.yellastrodev.dwij.utils

class LangFormats {
    companion object {
        fun getNumericPostfix(number: Int): String {
            var postLetter = ""
            val lastNumber = (number -
                    Math.round((number/10).toDouble())).toInt()
            if (lastNumber>4 || lastNumber == 0) postLetter = "ов"
            else if (lastNumber>1) postLetter = "а"
            return postLetter
        }
    }
}