/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.util

import java.sql.{Date, Timestamp}
import java.text.{DateFormat, SimpleDateFormat}
import java.util.{TimeZone, Calendar}

import org.apache.spark.unsafe.types.UTF8String

/**
 * Helper functions for converting between internal and external date and time representations.
 * Dates are exposed externally as java.sql.Date and are represented internally as the number of
 * dates since the Unix epoch (1970-01-01). Timestamps are exposed externally as java.sql.Timestamp
 * and are stored internally as longs, which are capable of storing timestamps with 100 nanosecond
 * precision.
 */
object DateTimeUtils {
  // see http://stackoverflow.com/questions/466321/convert-unix-timestamp-to-julian
  final val JULIAN_DAY_OF_EPOCH = 2440587  // and .5
  final val SECONDS_PER_DAY = 60 * 60 * 24L
  final val MICROS_PER_SECOND = 1000L * 1000L
  final val NANOS_PER_SECOND = MICROS_PER_SECOND * 1000L

  final val MILLIS_PER_DAY = SECONDS_PER_DAY * 1000L

  // number of days in 400 years
  final val daysIn400Years: Int = 146097
  // number of days between 1.1.1970 and 1.1.2001
  final val to2001 = -11323

  // this is year -17999, calculation: 50 * daysIn400Year
  final val YearZero = -17999
  final val toYearZero = to2001 + 7304850

  @transient lazy val defaultTimeZone = TimeZone.getDefault

  // Java TimeZone has no mention of thread safety. Use thread local instance to be safe.
  private val threadLocalLocalTimeZone = new ThreadLocal[TimeZone] {
    override protected def initialValue: TimeZone = {
      Calendar.getInstance.getTimeZone
    }
  }

  // `SimpleDateFormat` is not thread-safe.
  private val threadLocalTimestampFormat = new ThreadLocal[DateFormat] {
    override def initialValue(): SimpleDateFormat = {
      new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    }
  }

  // `SimpleDateFormat` is not thread-safe.
  private val threadLocalDateFormat = new ThreadLocal[DateFormat] {
    override def initialValue(): SimpleDateFormat = {
      new SimpleDateFormat("yyyy-MM-dd")
    }
  }

  // we should use the exact day as Int, for example, (year, month, day) -> day
  def millisToDays(millisUtc: Long): Int = {
    // SPARK-6785: use Math.floor so negative number of days (dates before 1970)
    // will correctly work as input for function toJavaDate(Int)
    val millisLocal = millisUtc + threadLocalLocalTimeZone.get().getOffset(millisUtc)
    Math.floor(millisLocal.toDouble / MILLIS_PER_DAY).toInt
  }

  // reverse of millisToDays
  def daysToMillis(days: Int): Long = {
    val millisUtc = days.toLong * MILLIS_PER_DAY
    millisUtc - threadLocalLocalTimeZone.get().getOffset(millisUtc)
  }

  def dateToString(days: Int): String =
    threadLocalDateFormat.get.format(toJavaDate(days))

  // Converts Timestamp to string according to Hive TimestampWritable convention.
  def timestampToString(us: Long): String = {
    val ts = toJavaTimestamp(us)
    val timestampString = ts.toString
    val formatted = threadLocalTimestampFormat.get.format(ts)

    if (timestampString.length > 19 && timestampString.substring(19) != ".0") {
      formatted + timestampString.substring(19)
    } else {
      formatted
    }
  }

  def stringToTime(s: String): java.util.Date = {
    if (!s.contains('T')) {
      // JDBC escape string
      if (s.contains(' ')) {
        Timestamp.valueOf(s)
      } else {
        Date.valueOf(s)
      }
    } else if (s.endsWith("Z")) {
      // this is zero timezone of ISO8601
      stringToTime(s.substring(0, s.length - 1) + "GMT-00:00")
    } else if (s.indexOf("GMT") == -1) {
      // timezone with ISO8601
      val inset = "+00.00".length
      val s0 = s.substring(0, s.length - inset)
      val s1 = s.substring(s.length - inset, s.length)
      if (s0.substring(s0.lastIndexOf(':')).contains('.')) {
        stringToTime(s0 + "GMT" + s1)
      } else {
        stringToTime(s0 + ".0GMT" + s1)
      }
    } else {
      // ISO8601 with GMT insert
      val ISO8601GMT: SimpleDateFormat = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss.SSSz" )
      ISO8601GMT.parse(s)
    }
  }

  /**
   * Returns the number of days since epoch from from java.sql.Date.
   */
  def fromJavaDate(date: Date): Int = {
    millisToDays(date.getTime)
  }

  /**
   * Returns a java.sql.Date from number of days since epoch.
   */
  def toJavaDate(daysSinceEpoch: Int): Date = {
    new Date(daysToMillis(daysSinceEpoch))
  }

  /**
   * Returns a java.sql.Timestamp from number of micros since epoch.
   */
  def toJavaTimestamp(us: Long): Timestamp = {
    // setNanos() will overwrite the millisecond part, so the milliseconds should be
    // cut off at seconds
    var seconds = us / MICROS_PER_SECOND
    var micros = us % MICROS_PER_SECOND
    // setNanos() can not accept negative value
    if (micros < 0) {
      micros += MICROS_PER_SECOND
      seconds -= 1
    }
    val t = new Timestamp(seconds * 1000)
    t.setNanos(micros.toInt * 1000)
    t
  }

  /**
   * Returns the number of micros since epoch from java.sql.Timestamp.
   */
  def fromJavaTimestamp(t: Timestamp): Long = {
    if (t != null) {
      t.getTime() * 1000L + (t.getNanos().toLong / 1000) % 1000L
    } else {
      0L
    }
  }

  /**
   * Returns the number of microseconds since epoch from Julian day
   * and nanoseconds in a day
   */
  def fromJulianDay(day: Int, nanoseconds: Long): Long = {
    // use Long to avoid rounding errors
    val seconds = (day - JULIAN_DAY_OF_EPOCH).toLong * SECONDS_PER_DAY - SECONDS_PER_DAY / 2
    seconds * MICROS_PER_SECOND + nanoseconds / 1000L
  }

  /**
   * Returns Julian day and nanoseconds in a day from the number of microseconds
   */
  def toJulianDay(us: Long): (Int, Long) = {
    val seconds = us / MICROS_PER_SECOND + SECONDS_PER_DAY / 2
    val day = seconds / SECONDS_PER_DAY + JULIAN_DAY_OF_EPOCH
    val secondsInDay = seconds % SECONDS_PER_DAY
    val nanos = (us % MICROS_PER_SECOND) * 1000L
    (day.toInt, secondsInDay * NANOS_PER_SECOND + nanos)
  }

  /**
   * Parses a given UTF8 date string to the corresponding a corresponding [[Long]] value.
   * The return type is [[Option]] in order to distinguish between 0L and null. The following
   * formats are allowed:
   *
   * `yyyy`
   * `yyyy-[m]m`
   * `yyyy-[m]m-[d]d`
   * `yyyy-[m]m-[d]d `
   * `yyyy-[m]m-[d]d [h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]`
   * `yyyy-[m]m-[d]d [h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]Z`
   * `yyyy-[m]m-[d]d [h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]-[h]h:[m]m`
   * `yyyy-[m]m-[d]d [h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]+[h]h:[m]m`
   * `yyyy-[m]m-[d]dT[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]`
   * `yyyy-[m]m-[d]dT[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]Z`
   * `yyyy-[m]m-[d]dT[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]-[h]h:[m]m`
   * `yyyy-[m]m-[d]dT[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]+[h]h:[m]m`
   * `[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]`
   * `[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]Z`
   * `[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]-[h]h:[m]m`
   * `[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]+[h]h:[m]m`
   * `T[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]`
   * `T[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]Z`
   * `T[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]-[h]h:[m]m`
   * `T[h]h:[m]m:[s]s.[ms][ms][ms][us][us][us]+[h]h:[m]m`
   */
  def stringToTimestamp(s: UTF8String): Option[Long] = {
    if (s == null) {
      return None
    }
    var timeZone: Option[Byte] = None
    val segments: Array[Int] = Array[Int](1, 1, 1, 0, 0, 0, 0, 0, 0)
    var i = 0
    var currentSegmentValue = 0
    val bytes = s.getBytes
    var j = 0
    var digitsMilli = 0
    var justTime = false
    while (j < bytes.length) {
      val b = bytes(j)
      val parsedValue = b - '0'.toByte
      if (parsedValue < 0 || parsedValue > 9) {
        if (j == 0 && b == 'T') {
          justTime = true
          i += 3
        } else if (i < 2) {
          if (b == '-') {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else if (i == 0 && b == ':') {
            justTime = true
            segments(3) = currentSegmentValue
            currentSegmentValue = 0
            i = 4
          } else {
            return None
          }
        } else if (i == 2) {
          if (b == ' ' || b == 'T') {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else {
            return None
          }
        } else if (i == 3 || i == 4) {
          if (b == ':') {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else {
            return None
          }
        } else if (i == 5 || i == 6) {
          if (b == 'Z') {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
            timeZone = Some(43)
          } else if (b == '-' || b == '+') {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
            timeZone = Some(b)
          } else if (b == '.' && i == 5) {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else {
            return None
          }
          if (i == 6  && b != '.') {
            i += 1
          }
        } else {
          if (b == ':' || b == ' ') {
            segments(i) = currentSegmentValue
            currentSegmentValue = 0
            i += 1
          } else {
            return None
          }
        }
      } else {
        if (i == 6) {
          digitsMilli += 1
        }
        currentSegmentValue = currentSegmentValue * 10 + parsedValue
      }
      j += 1
    }

    segments(i) = currentSegmentValue

    while (digitsMilli < 6) {
      segments(6) *= 10
      digitsMilli += 1
    }

    if (!justTime && (segments(0) < 1000 || segments(0) > 9999 || segments(1) < 1 ||
        segments(1) > 12 || segments(2) < 1 || segments(2) > 31)) {
      return None
    }

    if (segments(3) < 0 || segments(3) > 23 || segments(4) < 0 || segments(4) > 59 ||
        segments(5) < 0 || segments(5) > 59 || segments(6) < 0 || segments(6) > 999999 ||
        segments(7) < 0 || segments(7) > 23 || segments(8) < 0 || segments(8) > 59) {
      return None
    }

    val c = if (timeZone.isEmpty) {
      Calendar.getInstance()
    } else {
      Calendar.getInstance(
        TimeZone.getTimeZone(f"GMT${timeZone.get.toChar}${segments(7)}%02d:${segments(8)}%02d"))
    }
    c.set(Calendar.MILLISECOND, 0)

    if (justTime) {
      c.set(Calendar.HOUR_OF_DAY, segments(3))
      c.set(Calendar.MINUTE, segments(4))
      c.set(Calendar.SECOND, segments(5))
    } else {
      c.set(segments(0), segments(1) - 1, segments(2), segments(3), segments(4), segments(5))
    }

    Some(c.getTimeInMillis * 1000 + segments(6))
  }

  /**
   * Parses a given UTF8 date string to the corresponding a corresponding [[Int]] value.
   * The return type is [[Option]] in order to distinguish between 0 and null. The following
   * formats are allowed:
   *
   * `yyyy`,
   * `yyyy-[m]m`
   * `yyyy-[m]m-[d]d`
   * `yyyy-[m]m-[d]d `
   * `yyyy-[m]m-[d]d *`
   * `yyyy-[m]m-[d]dT*`
   */
  def stringToDate(s: UTF8String): Option[Int] = {
    if (s == null) {
      return None
    }
    val segments: Array[Int] = Array[Int](1, 1, 1)
    var i = 0
    var currentSegmentValue = 0
    val bytes = s.getBytes
    var j = 0
    while (j < bytes.length && (i < 3 && !(bytes(j) == ' ' || bytes(j) == 'T'))) {
      val b = bytes(j)
      if (i < 2 && b == '-') {
        segments(i) = currentSegmentValue
        currentSegmentValue = 0
        i += 1
      } else {
        val parsedValue = b - '0'.toByte
        if (parsedValue < 0 || parsedValue > 9) {
          return None
        } else {
          currentSegmentValue = currentSegmentValue * 10 + parsedValue
        }
      }
      j += 1
    }
    segments(i) = currentSegmentValue
    if (segments(0) < 1000 || segments(0) > 9999 || segments(1) < 1 || segments(1) > 12 ||
        segments(2) < 1 || segments(2) > 31) {
      return None
    }
    val c = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
    c.set(segments(0), segments(1) - 1, segments(2), 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    Some((c.getTimeInMillis / MILLIS_PER_DAY).toInt)
  }

  /**
   * Returns the hour value of a given timestamp value. The timestamp is expressed in microseconds.
   */
  def getHours(timestamp: Long): Int = {
    val localTs = (timestamp / 1000) + defaultTimeZone.getOffset(timestamp / 1000)
    ((localTs / 1000 / 3600) % 24).toInt
  }

  /**
   * Returns the minute value of a given timestamp value. The timestamp is expressed in
   * microseconds.
   */
  def getMinutes(timestamp: Long): Int = {
    val localTs = (timestamp / 1000) + defaultTimeZone.getOffset(timestamp / 1000)
    ((localTs / 1000 / 60) % 60).toInt
  }

  /**
   * Returns the second value of a given timestamp value. The timestamp is expressed in
   * microseconds.
   */
  def getSeconds(timestamp: Long): Int = {
    ((timestamp / 1000 / 1000) % 60).toInt
  }

  private[this] def isLeapYear(year: Int): Boolean = {
    (year % 4) == 0 && ((year % 100) != 0 || (year % 400) == 0)
  }

  /**
   * Return the number of days since the start of 400 year period.
   * The second year of a 400 year period (year 1) starts on day 365.
   */
  private[this] def yearBoundary(year: Int): Int = {
    year * 365 + ((year / 4 ) - (year / 100) + (year / 400))
  }

  /**
   * Calculates the number of years for the given number of days. This depends
   * on a 400 year period.
   * @param days days since the beginning of the 400 year period
   * @return (number of year, days in year)
   */
  private[this] def numYears(days: Int): (Int, Int) = {
    val year = days / 365
    val boundary = yearBoundary(year)
    if (days > boundary) (year, days - boundary) else (year - 1, days - yearBoundary(year - 1))
  }

  /**
   * Calculates the year and and the number of the day in the year for the given
   * number of days. The given days is the number of days since 1.1.1970.
   *
   * The calculation uses the fact that the period 1.1.2001 until 31.12.2400 is
   * equals to the period 1.1.1601 until 31.12.2000.
   */
  private[this] def getYearAndDayInYear(daysSince1970: Int): (Int, Int) = {
    // add the difference (in days) between 1.1.1970 and the artificial year 0 (-17999)
    val daysNormalized = daysSince1970 + toYearZero
    val numOfQuarterCenturies = daysNormalized / daysIn400Years
    val daysInThis400 = daysNormalized % daysIn400Years + 1
    val (years, dayInYear) = numYears(daysInThis400)
    val year: Int = (2001 - 20000) + 400 * numOfQuarterCenturies + years
    (year, dayInYear)
  }

  /**
   * Returns the 'day in year' value for the given date. The date is expressed in days
   * since 1.1.1970.
   */
  def getDayInYear(date: Int): Int = {
    getYearAndDayInYear(date)._2
  }

  /**
   * Returns the year value for the given date. The date is expressed in days
   * since 1.1.1970.
   */
  def getYear(date: Int): Int = {
    getYearAndDayInYear(date)._1
  }

  /**
   * Returns the quarter for the given date. The date is expressed in days
   * since 1.1.1970.
   */
  def getQuarter(date: Int): Int = {
    var (year, dayInYear) = getYearAndDayInYear(date)
    if (isLeapYear(year)) {
      dayInYear = dayInYear - 1
    }
    if (dayInYear <= 90) {
      1
    } else if (dayInYear <= 181) {
      2
    } else if (dayInYear <= 273) {
      3
    } else {
      4
    }
  }

  /**
   * Returns the month value for the given date. The date is expressed in days
   * since 1.1.1970. January is month 1.
   */
  def getMonth(date: Int): Int = {
    var (year, dayInYear) = getYearAndDayInYear(date)
    if (isLeapYear(year)) {
      if (dayInYear == 60) {
        return 2
      } else if (dayInYear > 60) {
        dayInYear = dayInYear - 1
      }
    }

    if (dayInYear <= 31) {
      1
    } else if (dayInYear <= 59) {
      2
    } else if (dayInYear <= 90) {
      3
    } else if (dayInYear <= 120) {
      4
    } else if (dayInYear <= 151) {
      5
    } else if (dayInYear <= 181) {
      6
    } else if (dayInYear <= 212) {
      7
    } else if (dayInYear <= 243) {
      8
    } else if (dayInYear <= 273) {
      9
    } else if (dayInYear <= 304) {
      10
    } else if (dayInYear <= 334) {
      11
    } else {
      12
    }
  }

  /**
   * Returns the 'day of month' value for the given date. The date is expressed in days
   * since 1.1.1970.
   */
  def getDayOfMonth(date: Int): Int = {
    var (year, dayInYear) = getYearAndDayInYear(date)
    if (isLeapYear(year)) {
      if (dayInYear == 60) {
        return 29
      } else if (dayInYear > 60) {
        dayInYear = dayInYear - 1
      }
    }

    if (dayInYear <= 31) {
      dayInYear
    } else if (dayInYear <= 59) {
      dayInYear - 31
    } else if (dayInYear <= 90) {
      dayInYear - 59
    } else if (dayInYear <= 120) {
      dayInYear - 90
    } else if (dayInYear <= 151) {
      dayInYear - 120
    } else if (dayInYear <= 181) {
      dayInYear - 151
    } else if (dayInYear <= 212) {
      dayInYear - 181
    } else if (dayInYear <= 243) {
      dayInYear - 212
    } else if (dayInYear <= 273) {
      dayInYear - 243
    } else if (dayInYear <= 304) {
      dayInYear - 273
    } else if (dayInYear <= 334) {
      dayInYear - 304
    } else {
      dayInYear - 334
    }
  }

  /**
   * The number of days for each month (not leap year)
   */
  private val monthDays = Array(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

  /**
   * Returns the date value for the first day of the given month.
   * The month is expressed in months since year zero (17999 BC), starting from 0.
   */
  private def firstDayOfMonth(absoluteMonth: Int): Int = {
    val absoluteYear = absoluteMonth / 12
    var monthInYear = absoluteMonth - absoluteYear * 12
    var date = getDateFromYear(absoluteYear)
    if (monthInYear >= 2 && isLeapYear(absoluteYear + YearZero)) {
      date += 1
    }
    while (monthInYear > 0) {
      date += monthDays(monthInYear - 1)
      monthInYear -= 1
    }
    date
  }

  /**
   * Returns the date value for January 1 of the given year.
   * The year is expressed in years since year zero (17999 BC), starting from 0.
   */
  private def getDateFromYear(absoluteYear: Int): Int = {
    val absoluteDays = (absoluteYear * 365 + absoluteYear / 400 - absoluteYear / 100
      + absoluteYear / 4)
    absoluteDays - toYearZero
  }

  /**
   * Add date and year-month interval.
   * Returns a date value, expressed in days since 1.1.1970.
   */
  def dateAddMonths(days: Int, months: Int): Int = {
    val absoluteMonth = (getYear(days) - YearZero) * 12 + getMonth(days) - 1 + months
    val currentMonthInYear = absoluteMonth % 12
    val currentYear = absoluteMonth / 12
    val leapDay = if (currentMonthInYear == 1 && isLeapYear(currentYear + YearZero)) 1 else 0
    val lastDayOfMonth = monthDays(currentMonthInYear) + leapDay

    val dayOfMonth = getDayOfMonth(days)
    val currentDayInMonth = if (getDayOfMonth(days + 1) == 1 || dayOfMonth >= lastDayOfMonth) {
      // last day of the month
      lastDayOfMonth
    } else {
      dayOfMonth
    }
    firstDayOfMonth(absoluteMonth) + currentDayInMonth - 1
  }

  /**
   * Add timestamp and full interval.
   * Returns a timestamp value, expressed in microseconds since 1.1.1970 00:00:00.
   */
  def timestampAddInterval(start: Long, months: Int, microseconds: Long): Long = {
    val days = millisToDays(start / 1000L)
    val newDays = dateAddMonths(days, months)
    daysToMillis(newDays) * 1000L + start - daysToMillis(days) * 1000L + microseconds
  }

  /**
   * Returns the last dayInMonth in the month it belongs to. The date is expressed
   * in days since 1.1.1970. the return value starts from 1.
   */
  private def getLastDayInMonthOfMonth(date: Int): Int = {
    var (year, dayInYear) = getYearAndDayInYear(date)
    if (isLeapYear(year)) {
      if (dayInYear > 31 && dayInYear <= 60) {
        return 29
      } else if (dayInYear > 60) {
        dayInYear = dayInYear - 1
      }
    }
    if (dayInYear <= 31) {
      31
    } else if (dayInYear <= 59) {
      28
    } else if (dayInYear <= 90) {
      31
    } else if (dayInYear <= 120) {
      30
    } else if (dayInYear <= 151) {
      31
    } else if (dayInYear <= 181) {
      30
    } else if (dayInYear <= 212) {
      31
    } else if (dayInYear <= 243) {
      31
    } else if (dayInYear <= 273) {
      30
    } else if (dayInYear <= 304) {
      31
    } else if (dayInYear <= 334) {
      30
    } else {
      31
    }
  }

  /**
   * Returns number of months between time1 and time2. time1 and time2 are expressed in
   * microseconds since 1.1.1970.
   *
   * If time1 and time2 having the same day of month, or both are the last day of month,
   * it returns an integer (time under a day will be ignored).
   *
   * Otherwise, the difference is calculated based on 31 days per month, and rounding to
   * 8 digits.
   */
  def monthsBetween(time1: Long, time2: Long): Double = {
    val millis1 = time1 / 1000L
    val millis2 = time2 / 1000L
    val date1 = millisToDays(millis1)
    val date2 = millisToDays(millis2)
    // TODO(davies): get year, month, dayOfMonth from single function
    val dayInMonth1 = getDayOfMonth(date1)
    val dayInMonth2 = getDayOfMonth(date2)
    val months1 = getYear(date1) * 12 + getMonth(date1)
    val months2 = getYear(date2) * 12 + getMonth(date2)

    if (dayInMonth1 == dayInMonth2 || (dayInMonth1 == getLastDayInMonthOfMonth(date1)
      && dayInMonth2 == getLastDayInMonthOfMonth(date2))) {
      return (months1 - months2).toDouble
    }
    // milliseconds is enough for 8 digits precision on the right side
    val timeInDay1 = millis1 - daysToMillis(date1)
    val timeInDay2 = millis2 - daysToMillis(date2)
    val timesBetween = (timeInDay1 - timeInDay2).toDouble / MILLIS_PER_DAY
    val diff = (months1 - months2).toDouble + (dayInMonth1 - dayInMonth2 + timesBetween) / 31.0
    // rounding to 8 digits
    math.round(diff * 1e8) / 1e8
  }

  /*
   * Returns day of week from String. Starting from Thursday, marked as 0.
   * (Because 1970-01-01 is Thursday).
   */
  def getDayOfWeekFromString(string: UTF8String): Int = {
    val dowString = string.toString.toUpperCase
    dowString match {
      case "SU" | "SUN" | "SUNDAY" => 3
      case "MO" | "MON" | "MONDAY" => 4
      case "TU" | "TUE" | "TUESDAY" => 5
      case "WE" | "WED" | "WEDNESDAY" => 6
      case "TH" | "THU" | "THURSDAY" => 0
      case "FR" | "FRI" | "FRIDAY" => 1
      case "SA" | "SAT" | "SATURDAY" => 2
      case _ => -1
    }
  }

  /**
   * Returns the first date which is later than startDate and is of the given dayOfWeek.
   * dayOfWeek is an integer ranges in [0, 6], and 0 is Thu, 1 is Fri, etc,.
   */
  def getNextDateForDayOfWeek(startDate: Int, dayOfWeek: Int): Int = {
    startDate + 1 + ((dayOfWeek - 1 - startDate) % 7 + 7) % 7
  }

  /**
   * Returns last day of the month for the given date. The date is expressed in days
   * since 1.1.1970.
   */
  def getLastDayOfMonth(date: Int): Int = {
    var (year, dayInYear) = getYearAndDayInYear(date)
    if (isLeapYear(year)) {
      if (dayInYear > 31 && dayInYear <= 60) {
        return date + (60 - dayInYear)
      } else if (dayInYear > 60) {
        dayInYear = dayInYear - 1
      }
    }
    val lastDayOfMonthInYear = if (dayInYear <= 31) {
      31
    } else if (dayInYear <= 59) {
      59
    } else if (dayInYear <= 90) {
      90
    } else if (dayInYear <= 120) {
      120
    } else if (dayInYear <= 151) {
      151
    } else if (dayInYear <= 181) {
      181
    } else if (dayInYear <= 212) {
      212
    } else if (dayInYear <= 243) {
      243
    } else if (dayInYear <= 273) {
      273
    } else if (dayInYear <= 304) {
      304
    } else if (dayInYear <= 334) {
      334
    } else {
      365
    }
    date + (lastDayOfMonthInYear - dayInYear)
  }

  private val TRUNC_TO_YEAR = 1
  private val TRUNC_TO_MONTH = 2
  private val TRUNC_INVALID = -1

  /**
   * Returns the trunc date from original date and trunc level.
   * Trunc level should be generated using `parseTruncLevel()`, should only be 1 or 2.
   */
  def truncDate(d: Int, level: Int): Int = {
    if (level == TRUNC_TO_YEAR) {
      d - DateTimeUtils.getDayInYear(d) + 1
    } else if (level == TRUNC_TO_MONTH) {
      d - DateTimeUtils.getDayOfMonth(d) + 1
    } else {
      throw new Exception(s"Invalid trunc level: $level")
    }
  }

  /**
   * Returns the truncate level, could be TRUNC_YEAR, TRUNC_MONTH, or TRUNC_INVALID,
   * TRUNC_INVALID means unsupported truncate level.
   */
  def parseTruncLevel(format: UTF8String): Int = {
    if (format == null) {
      TRUNC_INVALID
    } else {
      format.toString.toUpperCase match {
        case "YEAR" | "YYYY" | "YY" => TRUNC_TO_YEAR
        case "MON" | "MONTH" | "MM" => TRUNC_TO_MONTH
        case _ => TRUNC_INVALID
      }
    }
  }
}
