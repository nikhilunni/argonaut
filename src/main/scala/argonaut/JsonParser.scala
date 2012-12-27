package argonaut

import annotation.tailrec
import scalaz._
import Scalaz._

object JsonParser {
  private[this] final val arrayOpenTokenInSome = ArrayOpenToken.some
  private[this] final val arrayCloseTokenInSome = ArrayCloseToken.some
  private[this] final val objectOpenTokenInSome = ObjectOpenToken.some
  private[this] final val objectCloseTokenInSome = ObjectCloseToken.some
  private[this] final val entrySeparatorTokenInSome = EntrySeparatorToken.some
  private[this] final val fieldSeparatorTokenInSome = FieldSeparatorToken.some
  private[this] final val stringBoundsOpenTokenInSome = StringBoundsOpenToken.some
  private[this] final val stringBoundsCloseTokenInSome = StringBoundsCloseToken.some
  private[this] final val booleanTrueTokenInSome = BooleanTrueToken.some
  private[this] final val booleanFalseTokenInSome = BooleanFalseToken.some
  private[this] final val nullTokenInSome = NullToken.some

  sealed abstract class JSONToken {
    def originalStringContent: String
  }
  sealed abstract class OpenToken extends JSONToken
  sealed abstract class CloseToken extends JSONToken
  case object ArrayOpenToken extends OpenToken { 
    final val originalStringContent = "["
  }
  case object ArrayCloseToken extends CloseToken { 
    final val originalStringContent = "]"
  }
  case object ObjectOpenToken extends OpenToken { 
    final val originalStringContent = "{" 
  }
  case object ObjectCloseToken extends CloseToken { 
    final val originalStringContent = "}" 
  }
  case object EntrySeparatorToken extends JSONToken { 
    final val originalStringContent = "," 
  }
  case object FieldSeparatorToken extends JSONToken { 
    final val originalStringContent = ":" 
  }
  case object StringBoundsOpenToken extends OpenToken { 
    final val originalStringContent = "\"" 
  }
  case object StringBoundsCloseToken extends CloseToken { 
    final val originalStringContent = "\"" 
  }
  case class NumberToken(originalStringContent: String) extends JSONToken
  sealed abstract class BooleanToken extends JSONToken
  case object BooleanTrueToken extends BooleanToken { 
    final val originalStringContent = "true" 
  }
  case object BooleanFalseToken extends BooleanToken { 
    final val originalStringContent = "false" 
  }
  case object NullToken extends JSONToken { 
    final val originalStringContent = "null" 
  }
  sealed abstract class StringPartToken extends JSONToken {
    def parsedStringContent: String
  }
  sealed case class UnicodeCharacterToken(unicodeSequence: String) extends StringPartToken {
    final def originalStringContent = "\\u" + unicodeSequence
    final def parsedStringContent = new java.lang.StringBuilder().appendCodePoint(Integer.valueOf(unicodeSequence, 16)).toString
  }
  sealed case class EscapedCharacterToken(originalStringContent: String, parsedStringContent: String) extends StringPartToken
  object EscapedCharacterToken {
    val charMap: Map[String, EscapedCharacterToken] = Map(
      "\\r" -> EscapedCharacterToken("\\r", "\r"),
      "\\n" -> EscapedCharacterToken("\\n", "\n"),
      "\\t" -> EscapedCharacterToken("\\t", "\t"),
      "\\b" -> EscapedCharacterToken("\\b", "\b"),
      "\\f" -> EscapedCharacterToken("\\f", "\f"),
      """\\""" -> EscapedCharacterToken("""\\""", """\"""),
      """\/""" -> EscapedCharacterToken("""\/""", """/"""),
      "\\\"" -> EscapedCharacterToken("\\\"", "\"")
    ) 
  }

  sealed case class NormalStringToken(originalStringContent: String) extends StringPartToken {
    final def parsedStringContent = originalStringContent
  }
  sealed case class UnexpectedContentToken(originalStringContent: String) extends JSONToken
  
  private[this] def excerpt(string: String, limit: Int = 50): String = {
    if (string.size > limit) {
      string.take(limit) + "..."
    } else {
      string
    }
  }

  private[this] def excerpt(tokens: List[JSONToken]): String = excerpt(tokens.map(_.originalStringContent).mkString)

  def parse(json: String): ValidationNEL[String, Json] = {
    expectValue(tokenize(json).toList).flatMap{streamAndValue =>
      if (streamAndValue._1.isEmpty) {
        streamAndValue._2.successNel
      } else {
        "JSON contains invalid suffix content: %s".format(excerpt(streamAndValue._1)).failNel
      }
    }
  }

  def tokenize(json: String): List[JSONToken] = tokenize(none, json).reverse

  private[this] final def expectedSpacerToken(stream: List[JSONToken], token: JSONToken, failMessage: String): ValidationNEL[String, List[JSONToken]] = {
    stream.headOption match {
      case Some(`token`) => stream.tail.successNel
      case _ => "%s but found: %s".format(failMessage, excerpt(stream)).failNel
    }
  }
  
  private[this] final def expectStringOpen(stream: List[JSONToken]) = expectedSpacerToken(stream, StringBoundsOpenToken, "Expected string bounds")

  private[this] final def expectStringClose(stream: List[JSONToken]) = expectedSpacerToken(stream, StringBoundsCloseToken, "Expected string bounds")

  private[this] final def expectArrayOpen(stream: List[JSONToken]) = expectedSpacerToken(stream, ArrayOpenToken, "Expected array open token")

  private[this] final def expectArrayClose(stream: List[JSONToken]) = expectedSpacerToken(stream, ArrayCloseToken, "Expected array close token")

  private[this] final def expectObjectOpen(stream: List[JSONToken]) = expectedSpacerToken(stream, ObjectOpenToken, "Expected object open token")

  private[this] final def expectObjectClose(stream: List[JSONToken]) = expectedSpacerToken(stream, ObjectCloseToken, "Expected object close token")

  private[this] final def expectEntrySeparator(stream: List[JSONToken]) = expectedSpacerToken(stream, EntrySeparatorToken, "Expected entry separator token")

  private[this] final def expectFieldSeparator(stream: List[JSONToken]) = expectedSpacerToken(stream, FieldSeparatorToken, "Expected field separator token")
  
  private[this] final def expectObject(stream: List[JSONToken]): ValidationNEL[String, (List[JSONToken], JObject)] = {
    for {
      afterObjectOpen <- expectObjectOpen(stream)
      streamAndFields <- expectObjectField(true, (afterObjectOpen, List.empty).successNel)
      mappedVectorAndFields = streamAndFields.copy(_2 = streamAndFields._2.map(pair => (pair._1.s, pair._2)))
    } yield (streamAndFields._1, JObject(JsonObject(InsertionMap(mappedVectorAndFields._2.reverse: _*))))
  }
  
  private[this] final def expectArray(stream: List[JSONToken]): ValidationNEL[String, (List[JSONToken], JArray)] = {
    for {
      afterArrayOpen <- expectArrayOpen(stream)
      streamAndFields <- expectArrayField(true, (afterArrayOpen, List.empty).successNel)
    } yield (streamAndFields._1, JArray(streamAndFields._2.reverse))
  }

  private[this] final def expectValue(stream: List[JSONToken]): ValidationNEL[String, (List[JSONToken], Json)] = {
    stream match {
      case ArrayOpenToken :: _ => expectArray(stream)
      case ObjectOpenToken :: _ => expectObject(stream)
      case StringBoundsOpenToken :: _ => expectString(stream)
      case BooleanTrueToken :: tail => (tail, JBool(true)).successNel
      case BooleanFalseToken :: tail => (tail, JBool(false)).successNel
      case NullToken :: tail => (tail, JNull).successNel
      case NumberToken(numberText) :: tail => {
        numberText
          .parseDouble
          .fold(nfe => "Value [%s] cannot be parsed into a number.".format(numberText).failNel,
                doubleValue => (tail, JNumber(JsonNumber(doubleValue))).successNel)
      }
      case UnexpectedContentToken(excerpt) :: _ => "Unexpected content found: %s".format(excerpt).failNel
      case unexpectedToken :: _ => "Unexpected content found: %s".format(excerpt(stream)).failNel
      case Nil => "JSON terminates unexpectedly".failNel
    }
  }

  @tailrec private[this] final def expectArrayField(first: Boolean, currentVector: ValidationNEL[String, (List[JSONToken], List[Json])]): ValidationNEL[String, (List[JSONToken], List[Json])] = {
    currentVector match {
      case Success((stream, fields)) => {
        stream.headOption match {
          case Some(ArrayCloseToken) => (stream.tail, fields).successNel
          case _ => {
            expectArrayField(false, for {
              afterEntrySeparator <- if (first) stream.successNel[String] else expectEntrySeparator(stream)
              streamAndValue <- expectValue(afterEntrySeparator)
            } yield (streamAndValue._1, streamAndValue._2 :: fields))
          }
        }
      }
      case _ => currentVector
    }
  }
  
  @tailrec private[this] final def expectObjectField(first: Boolean, currentVector: ValidationNEL[String, (List[JSONToken], List[(JString, Json)])]): ValidationNEL[String, (List[JSONToken], List[(JString, Json)])] = {
    currentVector match {
      case Success((stream, fields)) => {
        stream match {
          case ObjectCloseToken :: tail => (tail, fields).successNel
          case _ => {
            expectObjectField(false, for {
              afterEntrySeparator <- if (first) stream.successNel[String] else expectEntrySeparator(stream)
              streamAndKey <- expectString(afterEntrySeparator)
              afterFieldSeperator <- expectFieldSeparator(streamAndKey._1)
              streamAndValue <- expectValue(afterFieldSeperator)
            } yield (streamAndValue._1, (streamAndKey._2, streamAndValue._2) :: fields))
          }
        }
      }
      case _ => currentVector
    }
  }

  private[this] final def expectString(stream: List[JSONToken]): ValidationNEL[String, (List[JSONToken], JString)] = {
    for {
      afterOpen <- expectStringOpen(stream)
      elements <- afterOpen.span(jsonToken => jsonToken.isInstanceOf[StringPartToken]).successNel[String]
      afterClose <- expectStringClose(elements._2)
    } yield (afterClose, JString(elements._1.collect{case stringPart: StringPartToken => stringPart.parsedStringContent}.mkString))
  }

  private[this] final def unexpectedContent(json: String) = List(UnexpectedContentToken(json.take(10)))

  private[this] final def parseNumber(json: String): Option[(NumberToken, String)] = {
    val (possibleNumber, remainder) = json.span(char => (char >= '0' && char <= '9') || char == '+' || char == '-' || char == 'e' || char == 'E' || char == '.')
    if (possibleNumber.isEmpty) None
    else (NumberToken(possibleNumber), remainder).some
  }
  
  @tailrec private[this] final def tokenize(previousToken: Option[JSONToken], json: String, current: List[JSONToken] = List.empty): List[JSONToken] = {
    if (json.isEmpty) current
    else {
      previousToken match {
        case Some(StringBoundsOpenToken) | Some(_: StringPartToken) => {
          if (json.head == '"') {
            tokenize(stringBoundsCloseTokenInSome, json.tail, StringBoundsCloseToken :: current)
          } else if (json.startsWith("""\""")) {
            if (json.startsWith("\\u")) {
              val possibleUnicodeSequence = json.drop(2).take(4)
              if (possibleUnicodeSequence.forall(char => (char >= 'a' && char <= 'f') || (char >= 'A' && char <= 'F') || (char >= '0' && char <= '9'))) {
                val unicodeCharToken = UnicodeCharacterToken(possibleUnicodeSequence)
                tokenize(unicodeCharToken.some, json.drop(6), unicodeCharToken :: current)
              } else unexpectedContent(json)
            } else {
              EscapedCharacterToken.charMap.get(json.take(2)) match {
                case escapedSome@ Some(escapedCharToken) => tokenize(escapedSome, json.drop(2), escapedCharToken :: current)
                case _ => unexpectedContent(json)
              }
            }
          } else {
            val (prefix: String, suffix: String) = json.span(char => !char.isControl && char != '"' && char != '\\')
            val normalStringToken = NormalStringToken(prefix)
            suffix.headOption match {
              case Some('\"') | Some('\\') => tokenize(normalStringToken.some, suffix, normalStringToken :: current)
              case None => normalStringToken :: current
              case _ => {
                unexpectedContent(suffix)
              }
            }
          }
        }
        case _ => {
          val jsonHead = json.head
          jsonHead match {
            case '[' => tokenize(arrayOpenTokenInSome, json.tail, ArrayOpenToken :: current)
            case ']' => tokenize(arrayCloseTokenInSome, json.tail, ArrayCloseToken :: current)
            case '{' => tokenize(objectOpenTokenInSome, json.tail, ObjectOpenToken :: current)
            case '}' => tokenize(objectCloseTokenInSome, json.tail, ObjectCloseToken :: current)
            case ':' => tokenize(fieldSeparatorTokenInSome, json.tail, FieldSeparatorToken :: current)
            case ',' => tokenize(entrySeparatorTokenInSome, json.tail, EntrySeparatorToken :: current)
            case '"' => tokenize(stringBoundsOpenTokenInSome, json.tail, StringBoundsOpenToken :: current)
            case ' ' => tokenize(previousToken, json.tail, current)
            case '\r' => tokenize(previousToken, json.tail, current)
            case '\n' => tokenize(previousToken, json.tail, current)
            case _ => {
              json match {
                case trueStartingJSON if trueStartingJSON.startsWith("true") => tokenize(booleanTrueTokenInSome, json.drop(4), BooleanTrueToken :: current)
                case falseStartingJSON if falseStartingJSON.startsWith("false") => tokenize(booleanFalseTokenInSome, json.drop(5), BooleanFalseToken :: current)
                case nullStartingJSON if nullStartingJSON.startsWith("null") => tokenize(nullTokenInSome, json.drop(4), NullToken :: current)
                case _ => {
                  parseNumber(json) match {
                    case Some((numberToken, remainder)) => tokenize(numberToken.some, remainder, numberToken :: current)
                    case _ => unexpectedContent(json)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}