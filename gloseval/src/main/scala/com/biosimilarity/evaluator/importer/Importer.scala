// -*- mode: Scala;-*-
// Filename:    Importer.scala
// Authors:     lgm
// Creation:    Tue Jan 19 16:49:16 2016
// Copyright:   Not supplied
// Description:
// ------------------------------------------------------------------------

package com.biosimilarity.evaluator.importer

import java.io.File
import java.net.URI
import java.util.UUID

import com.biosimilarity.evaluator.api._
import com.biosimilarity.evaluator.distribution.EvalConfigWrapper
import com.biosimilarity.evaluator.importer.models._
import com.biosimilarity.evaluator.spray.srp.ConversionUtils._
import com.biosimilarity.evaluator.spray.srp.SRPClient
import com.biosimilarity.evaluator.util._
import com.biosimilarity.evaluator.util.mongo.MongoQuery
import org.json4s.JsonAST.{JObject, JValue}
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write

import scalaj.http.{Http, HttpOptions}

class Importer(host: URI) {

  implicit val formats = org.json4s.DefaultFormats

  // maps loginId to agentURI
  private val agentsById = scala.collection.mutable.Map[String, String]()

  // maps loginId to sessionURI
  private val sessionsById = scala.collection.mutable.Map[String, String]()

  // maps src+trgt to label
  private val cnxnLabels = scala.collection.mutable.Map[String, String]()

  private val labels = scala.collection.mutable.Map[String, LabelDesc]()

  private def resolveLabel(id: String): LabelDesc = labels(id)

  private def glosevalPost(data: RequestContent): String = {
    glosevalPost(data.asRequest)
  }

  private def glosevalPost(msg : Request): String = {
    val requestBody = write(msg)
    glosevalPost(requestBody)
  }

  private def glosevalPost(msgType: String, data: JValue): String = {
    println(s"REQUEST: $msgType")
    val requestBody = write( ("msgType" -> msgType) ~ ("content" -> data) )
    glosevalPost(requestBody)
  }

  private def glosevalPost(requestBody: String): String = {
    println(s"REQUEST BODY: $requestBody")

    val req = Http(host.toString)
      .timeout(1000, 600000)
      .header("Content-Type", "application/json")
      .option(HttpOptions.allowUnsafeSSL)
      .postData(requestBody)
    val response = req.asString.body

    println(s"RESPONSE BODY: $response")
    if (response.startsWith("Malformed request")) throw new Exception(response)
    response
  }

  def makeAliasURI(alias: String) = s"alias://$alias/alias"

  var terminateLongPoll = false

  def longPoll(): Thread = {
    println("initiating long-polling")
    new Thread(new Runnable() {
      override def run() {
        while (!terminateLongPoll) {
          val tmp = sessionsById.clone()
          while (tmp.nonEmpty) {
            tmp.clone().foreach {
              case (id, session) =>
                if (!terminateLongPoll) {
                  try {
                    val js = glosevalPost(SessionPing(session))
                    val arr = parse(js).extract[List[JObject]]
                    arr.foreach(v => {
                      val typ = (v \ "msgType").extract[String]
                      typ match {
                        case "sessionPong" => tmp.remove(id)
                        case "connectionProfileResponse" => ()
                        case "addAliasLabelsResponse" => ()
                        case "beginIntroductionResponse" => ()
                        case "establishConnectionResponse" => ()
                        case "evalComplete" => ()
                        case "evalSubscribeResponse" => ()
                        case "omniBalanceResponse" => ()
                        case _ =>
                          println("WARNING - handler not provided for server sent message type : " + typ)
                      }
                    })
                  } catch {
                    case ex: Throwable =>
                      println("exception during SessionPing : " + ex)
                      tmp.remove(id)
                      terminateLongPoll = true
                  }
                }
            }
          }
          if (!terminateLongPoll) {
            // println("longpoll sleeping")
            Thread.sleep(3000)
          }
        }
      }
    })
  }

  def expect(msgType: String, session: String): Option[JValue] = {
    println("Sending Ping")
    val js = glosevalPost(SessionPing(session))
    var rslt: Option[JValue] = None
    var done = false
    while (!done) {
      val arr = parse(js).extract[List[JValue]]
      arr.foreach(v => {
        val typ = (v \ "msgType").extract[String]
        typ match {
          case "sessionPong" => done = true
          case `msgType` =>
            done = true
            rslt = Some(v \ "content")
          case _ => ()
        }
      })
    }
    rslt
  }

  def expectAll(session: String): List[JValue] = {
    println("Sending Ping")
    var rslt: List[JValue] = Nil
    var done = false
    while (!done) {
      val js = glosevalPost(SessionPing(session))
      val arr = parse(js).extract[List[JValue]]
      arr.foreach(v => {
        val typ = (v \ "msgType").extract[String]
        typ match {
          case "sessionPong" =>
            done = true
          case _ =>
            rslt = v :: rslt
        }
      })
    }
    rslt.reverse
  }

  def createAgent(agent: AgentDesc): Option[String] = {
    val eml = agent.email + (if (agent.email.contains("@")) "" else "@livelygig.com")
    val jsonBlob = parse(agent.jsonBlob).extract[JObject]
    val srpClient = new SRPClient()
    srpClient.init
    val r1 = parse(glosevalPost(CreateUserStep1Request(eml))).extract[Response]
    r1.extractResponseContent match {
      case ApiError(reason) =>
        println(s"create user, step 1, failed, reason : $reason")
        None
      case CreateUserStep1Response(salt) =>
        srpClient.calculateX(eml, agent.pwd, salt)
        val r2 = parse(glosevalPost(CreateUserStep2Request("noconfirm:"+eml, salt, srpClient.generateVerifier, jsonBlob))).extract[Response]
        r2.extractResponseContent match {
          case ApiError(reason) =>
            println(s"create user, step 2, failed, reason : $reason")
            None
          case CreateUserStep2Response(agentURI) =>
            Some(agentURI)
          case _ => throw new Exception("Unspecified response")
        }
      case _ => throw new Exception("Unspecified response")
    }
  }

  def createSession(email: String, pwd: String): Option[String] = {
    val srpClient = new SRPClient()
    srpClient.init
    val emluri = "agent://email/"+email
    val r1 = parse(glosevalPost(InitializeSessionStep1Request(s"$emluri?A=${srpClient.calculateAHex}")))
      .extract[Response]
    r1.extractResponseContent match {
      case ApiError(reason) =>
        println(s"initialize session, step 1, failed, reason : $reason")
        None
      case InitializeSessionStep1Response(salt, bval) =>
        srpClient.calculateX(email, pwd, salt)
        val r2 = parse(glosevalPost(InitializeSessionStep2Request(s"$emluri?M=${srpClient.calculateMHex(bval)}")))
          .extract[Response]
        r2.extractResponseContent match {
          case ApiError(reason) =>
            println(s"initialize session, step 2, failed, reason : $reason")
            None
          case InitializeSessionResponse(sessionURI, _, _, _, _, _, _, _, m2) =>
            if(srpClient.verifyServerEvidenceMessage(fromHex(m2))) Some(sessionURI)
            else throw new Exception("Authentication failed on client")
          case _ => throw new Exception("Unspecified response")
        }
      case _ => throw new Exception("Unspecified response")
    }
  }

  def makeAgent(agent: AgentDesc): Unit = {
    createAgent(agent) match {
      case None => ()
      case Some(agentURI) =>
        val agentCap = agentURI.replace("agent://cap/", "").slice(0, 36)
        agentsById.put(agent.id, agentCap)
        createSession(agent.email, agent.pwd) match {
          case None => throw new Exception("Create session failure.")
          case Some(session) =>
            sessionsById.put(agent.id, session)

            agent.aliasLabels match {
              case None =>()
              case Some(l) =>
                val lbls = l.map(lbl => makeLabel(LabelDesc.extractFrom(lbl)).toTermString(resolveLabel))
                glosevalPost(AddAliasLabelsRequest(session, "alias", lbls))
            }
        }
      case _ => throw new Exception("Unspecified response")
    }
  }

  def makeLabel(label: LabelDesc): LabelDesc = {

    def matchFunctor(name: String, lbl: LabelDesc): Boolean = {
      lbl match {
        case ComplexLabelDesc(_, fnctr, _) => name == fnctr
        case SimpleLabelDesc(_, _, Some(fnctr)) => name == fnctr
        case _ => false
      }
    }

    def reorderComponents(lbl: LabelDesc): LabelDesc = {
      lbl match {
        case ComplexLabelDesc(id, "leaf", lbls) =>
          val (tp, r) = lbls.partition(matchFunctor("text", _))
          if (tp.length > 1) throw new Exception("label must contain at most one text field")
          val (dp, r2) = r.partition(matchFunctor("display", _))
          if (dp.length > 1) throw new Exception("label must contain at most one display field")
          val lbls2 = tp ++ dp ++ r2
          ComplexLabelDesc(id, "leaf", lbls2)
        case _ => lbl
      }
    }

    label match {
      case smpl: SimpleLabelDesc =>
        smpl.id.foreach(s => labels.put(s, smpl))
        smpl
      case cmplx: ComplexLabelDesc =>
        val rslt = reorderComponents(cmplx)
        cmplx.id.foreach(s => labels.put(s, rslt))
        rslt
      case ref: LabelRef => ref //labels(ref.label)  // throw if not present??
    }

  }

  def makeCnxn(sessionId: String, connection: ConnectionDesc): Unit = {
    val sourceId = agentsById(connection.src.replace("agent://", ""))
    val sourceURI = makeAliasURI(sourceId)
    val targetId = agentsById(connection.trgt.replace("agent://", ""))
    val targetURI = makeAliasURI(targetId)
    val cnxnLabel = UUID.randomUUID().toString

    if (!cnxnLabels.contains(sourceId + targetId)) {
      glosevalPost(EstablishConnectionRequest(sessionId, sourceURI, targetURI, cnxnLabel))
      cnxnLabels.put(sourceId + targetId, cnxnLabel)
      cnxnLabels.put(targetId + sourceId, cnxnLabel)
    }
  }

  def makePost(post: PostDesc): Unit = {
    var cnxns: List[Connection] = Nil

    val sourceId = agentsById(post.src)
    val sourceAlias = makeAliasURI(sourceId)
    val sourceSession = sessionsById(post.src)

    val selfcnxn = Connection("agent://" + sourceId, "agent://" + sourceId, "alias")

    post.trgts.foreach(trgt => {
      val targetId = agentsById(trgt)
      val lbl = cnxnLabels.get(sourceId + targetId) match {
        case Some(l) => l
        case None => throw new Exception(s"Cannot post from source '${post.src}' to target '${trgt}' as no connection has been established")
      }
      val trgtAlias = makeAliasURI(agentsById(trgt))
      cnxns = Connection(sourceAlias, trgtAlias, lbl) :: cnxns
    })

    val cont = EvalSubscribeContent(selfcnxn :: cnxns, post.label, Some(post.value), Some(post.uid))
    glosevalPost(EvalSubscribeRequest(sourceSession, EvalSubscribeExpression("insertContent", cont)))

  }

  private def checkPoll() = {
    if (terminateLongPoll) {
      rslt = 2
      throw new Exception("polling thread has terminated")
    }
  }

  private var rslt = 0
  private var thrd: Option[Thread] = None
  def start() = {
    terminateLongPoll = false
    val t = longPoll()
    t.start()
    thrd = Some(t)

  }

  def stop() = {
    thrd match {
      case Some(t) =>
        terminateLongPoll = true
        thrd = None
      case None => ()
    }
  }

  def importData(dataJson: String, email: String, password: String) = {
    val dataset = parse(dataJson).extract[DataSetDesc]
    try {
      val adminSession = createSession(email, password) match {
        case Some(s) => s
        case None    => throw new Exception("Unable to create admin session")
      }
      sessionsById.put("admin", adminSession)
      thrd match {
        case Some(t) => ()
        case None => throw new Exception("polling not started")
      }
      println(s"using admin session URI: $adminSession")
      dataset.labels match {
        case Some(lbls) => lbls.foreach(l => {
          checkPoll()
          makeLabel(LabelDesc.extractFrom(l))
        })
        case None => ()
      }
      dataset.agents.foreach(a => {
        checkPoll()
        makeAgent(a)
      })
      dataset.cnxns match {
        case Some(cnxns) => cnxns.foreach(cnxn => {
          checkPoll()
          makeCnxn(adminSession, cnxn)
        })
        case None => ()
      }
      dataset.posts match {
        case Some(posts) => posts.foreach(p => {
          checkPoll()
          makePost(p)
        })
        case None => ()
      }
    } catch {
      case ex: Throwable =>
        println("ERROR : " + ex)
        rslt = Math.max(1, rslt)
    } finally {
      terminateLongPoll = true
      sessionsById.foreach(pr => glosevalPost(CloseSessionRequest(pr._2)))
    }
    rslt
  }

}

object Importer {

  def fromFile(dataJsonFile: File,
               host: URI = EvalConfigWrapper.serviceHostURI,
               email: String = EvalConfigWrapper.email,
               password: String = EvalConfigWrapper.password): Int = {
    println(s"Importing file: $dataJsonFile")
    val dataJson: String = scala.io.Source.fromFile(dataJsonFile).getLines.map(_.trim).mkString
    val imp              = new Importer(host)
    imp.start()
    val rslt = imp.importData(dataJson, email, password)
    imp.stop()
    val qry = new MongoQuery()
    qry.printAliasCnxns()
    println("Import file returning : " + rslt)
    rslt
  }

  def fromTestData(testDataFilename: String = EvalConfigWrapper.serviceDemoDataFilename,
                   host: URI = EvalConfigWrapper.serviceHostURI,
                   email: String = EvalConfigWrapper.email,
                   password: String = EvalConfigWrapper.password): Int =
    fromFile(testDir.resolve(s"$testDataFilename.json").toFile, host, email, password)
}
