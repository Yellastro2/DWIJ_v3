package com.yellastrodev.yandexmusiclib.yUtils

import java.util.logging.FileHandler
import java.util.logging.Logger

class yLog {

	companion object{
		fun log(f_name: String): Logger {
			val yLog = Logger.getLogger(f_name)
			//yLog.addHandler(FileHandler("%t/$f_name._%u_%g.txt",1024 * 1024, 100,true))
			return yLog
		}
	}
}