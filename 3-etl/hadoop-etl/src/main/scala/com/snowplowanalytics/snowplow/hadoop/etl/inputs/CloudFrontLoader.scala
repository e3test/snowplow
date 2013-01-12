/*
 * Copyright (c) 2012 SnowPlow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.hadoop.etl
package inputs

// Scalaz
import scalaz._
import Scalaz._

// Apache Commons
import org.apache.commons.lang.StringUtils

// Joda-Time
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}

/**
 * Module to hold specific helpers related to the
 * CloudFront input format.
 *
 * By "CloudFront input format", we mean the
 * CloudFront access log format for download
 * distributions (not streaming), September
 * 2012 release but with support for the pre-
 * September 2012 format as well.
 *
 * For more details on this format, please see:
 * http://docs.amazonwebservices.com/AmazonCloudFront/latest/DeveloperGuide/AccessLogs.html#LogFileFormat
 */
object CloudFrontLoader extends CollectorLoader {

  // For the MaybeCanonicalInput type
  import CanonicalInput._

  // The encoding used on CloudFront logs
  private val CfEncoding = "UTF-8"

  // Define the regular expression for extracting the fields
  // Adapted from Amazon's own cloudfront-loganalyzer.tgz
  private val CfRegex = {
    val w = "[\\s]+"   // Whitespace regex
    val ow = "(?:" + w // Optional whitespace begins
    
    // Our regex follows
    (   "([\\S]+)" +   // Date          / date
    w + "([\\S]+)" +   // Time          / time
    w + "([\\S]+)" +   // EdgeLocation  / x-edge-location
    w + "([\\S]+)" +   // BytesSent     / sc-bytes
    w + "([\\S]+)" +   // IPAddress     / c-ip
    w + "([\\S]+)" +   // Operation     / cs-method
    w + "([\\S]+)" +   // Domain        / cs(Host)
    w + "([\\S]+)" +   // Object        / cs-uri-stem
    w + "([\\S]+)" +   // HttpStatus    / sc-status
    w + "([\\S]+)" +   // Referer       / cs(Referer)
    w + "([\\S]+)" +   // UserAgent     / cs(User Agent)
    w + "([\\S]+)" +   // Querystring   / cs-uri-query
    ow + "[\\S]+"  +   // CookieHeader  / cs(Cookie)         added 12 Sep 2012
    w +  "[\\S]+"  +   // ResultType    / x-edge-result-type added 12 Sep 2012
    w +  "[\\S]+)?").r // X-Amz-Cf-Id   / x-edge-request-id  added 12 Sep 2012
  }

  /**
   * Converts the source string into a 
   * CanonicalInput.
   *
   * TODO: need to change this to
   * handling some sort of validation
   * object.
   *
   * @param line A line of data to convert
   * @return either a set of validation
   *         errors or an Option-boxed
   *         CanonicalInput object, wrapped
   *         in a Scalaz ValidatioNEL.
   */
  def toCanonicalInput(line: String): MaybeCanonicalInput[String] = line match {
    
    // 1. Header row
    case h if (h.startsWith("#Version:") ||
               h.startsWith("#Fields:"))    => None.success
    
    // 2. Row matches CloudFront format
    case CfRegex(date,
                 time,
                 _,
                 _,
                 ipAddress,
                 _,
                 _,
                 objct,
                 _,
                 referer,
                 userAgent,
                 querystring,
                 _,
                 _,
                 _) => {

      // Is this a request for the tracker? Might be a browser favicon request or similar
      if (!isIceRequest(objct)) return None.success

      // Build the Joda-Time
      val timestamp = toDateTime(date, time) getOrElse { return "Oh no".failNel } // TODO: fix reason

      val qs = toOption(querystring)
      if (qs == None) return "Oh no".failNel // TODO: needs fixing
      // Finally check that we have a querystring
      // case None => "supplied querystring was null, cannot extract GET payload".fail
      // case Some("") => "supplied querystring was empty, cannot extract GET payload".fail

      TrackerPayload.extractGetPayload(qs.get, CfEncoding) match { // Yech
        case Failure(f) =>
          "Oh no".failNel // TODO: add in the reason obv
        case Success(s) => 
          Some(CanonicalInput(timestamp = timestamp,
                              payload   = GetPayload(s),
                              ipAddress = toOption(ipAddress),
                              userAgent = toOption(userAgent),
                              refererUri = toOption(referer) map toCleanUri,
                              userId = None)).success
      }
    }

    // 3. Row does not match CloudFront header or data row formats
    case _ => "Oh no".failNel // TODO: return a validation error so we can route this row to the bad row bin
  }

  /**
   * Converts a CloudFront log-format date and
   * a time to a Joda DateTime.
   *
   * @param date The CloudFront log-format date
   * @param time The CloudFront log-format time
   * @return the JodaTime, Option-boxed, or
   *         None if something went wrong
   */
  private def toDateTime(date: String, time: String): Option[DateTime] = try {
    Some(DateTime.parse("%sT%s".format(date, time))) // Add T to conform to UTC styles
  } catch {
    case iae: IllegalArgumentException => None // TODO: should really return an error
  }

  /**
   * Checks whether a String field is a hyphen
   * "-", which is used by CloudFront to signal
   * a null.
   *
   * @param field The field to check
   * @return True if the String was a hyphen "-"
   */
  private def toOption(field: String): Option[String] = Option(field) match {
    case Some("-") => None
    case Some("")  => None
    case s => s // Leaves any other Some(x) or None as-is
  }

  /**
   * 'Cleans' a string to make it parsable by
   * URLDecoder.decode.
   * 
   * The '%' character seems to be appended to the
   * end of some URLs in the CloudFront logs, causing
   * Exceptions when using URLDecoder.decode. Perhaps
   * a CloudFront bug?
   *
   * TODO: move this into a CloudFront-specific file
   *
   * @param s The String to clean
   * @return the cleaned string
   */
  private def toCleanUri(uri: String): String = 
    StringUtils.removeEnd(uri, "%")
}