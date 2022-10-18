/* Author: Fabian Huch, TU Muenchen

AFP submission system backend.
 */
package afp


import isabelle.*
import isabelle.HTML.*

import afp.Web_App.{FILE, Params}
import afp.Web_App.Params.{List_Key, Nest_Key, empty}
import afp.Web_App.HTML.*
import afp.Metadata.{Affiliation, Author, DOI, Email, Entry, Formatted, Homepage, License, Orcid, Reference, Topic, Unaffiliated}

import scala.collection.immutable.StringView
import scala.util.matching.Regex

import java.net.URL
import java.text.Normalizer
import java.time.LocalDate


object AFP_Submit {

  case class Validated[A] private(v: A, error: Option[String]) {
    def with_error(error: String): Validated[A] = Validated.error(v, error)
  }

  object Validated {
    def ok[A](value: A): Validated[A] = Validated(value, None)
    def error[A](value: A, msg: String): Validated[A] = Validated(value, Some(msg))
  }


  /* data model */

  object Model {
    object Related extends Enumeration {
      val DOI, Formatted = Value

      def from_string(s: String): Option[Value] = values.find(_.toString == s)
      def get(r: Reference): Value = r match {
        case afp.Metadata.DOI(_) => DOI
        case afp.Metadata.Formatted(_) => Formatted
      }
    }

    case class Entry(
      name: Validated[String] = Validated.ok(""),
      title: Validated[String] = Validated.ok(""),
      affils: Validated[List[Affiliation]] = Validated.ok(Nil),
      notifies: Validated[List[Email]] = Validated.ok(Nil),
      author_input: Validated[Option[Author]] = Validated.ok(None),
      notify_input: Validated[Option[Author]] = Validated.ok(None),
      topics: Validated[List[Topic]] = Validated.ok(Nil),
      topic_input: Validated[Option[Topic]] = Validated.ok(None),
      license: License,
      `abstract`: Validated[String] = Validated.ok(""),
      related: List[Reference] = Nil,
      related_kind: Option[Related.Value] = None,
      related_input: Validated[String] = Validated.ok("")
    ) {
      val used_affils: Set[Affiliation] = (affils.v ++ notifies.v).toSet
      val used_authors: Set[Author.ID] = used_affils.map(_.author)
    }

    case class Metadata(
      entries: Validated[List[Entry]] = Validated.ok(Nil),
      new_authors: Validated[List[Author]] = Validated.ok(Nil),
      new_author_input: Validated[String] = Validated.ok(""),
      new_author_orcid: Validated[String] = Validated.ok(""),
      new_affils: Validated[List[Affiliation]] = Validated.ok(Nil),
      new_affils_author: Option[Author] = None,
      new_affils_input: Validated[String] = Validated.ok(""),
      message: String = ""
    ) extends T {
      def update_entry(num: Int, entry: Entry): Metadata =
        this.copy(entries = Validated.ok(entries.v.updated(num, entry)))

      def updated_authors(old_authors: Map[Author.ID, Author]): Map[Author.ID, Author] =
        (old_authors ++ new_authors.v.map(author => author.id -> author)).map {
          case (id, author) =>
            val emails =
              author.emails ++ new_affils.v.collect { case e: Email if e.author == id => e }
            val homepages =
              author.homepages ++ new_affils.v.collect { case h: Homepage if h.author == id => h }
            id -> author.copy(emails = emails, homepages = homepages)
        }

      val used_affils: Set[Affiliation] = entries.v.toSet.flatMap(_.used_affils)
      val used_authors: Set[Author.ID] =
        new_affils.v.map(_.author).toSet ++ entries.v.flatMap(_.used_authors)
    }

    sealed trait T
    case object Invalid extends T
    case class Upload(metadata: Metadata, error: String) extends T
    case class Submitted(id: String) extends T
  }


  /* server */

  class Server(
    authors: Map[Author.ID, Metadata.Author],
    topics: List[Topic],
    licenses: Map[License.ID, License],
    entries: Set[Entry.Name],
    verbose: Boolean,
    progress: Progress,
    port: Int = 0,
    submission_dir: Path
  ) extends Web_App.Server[Model.T](port, verbose, progress) {
    /* endpoints */

    val SUBMIT = "/submit"
    val API_SUBMISSION = "/api/submission"
    val API_SUBMISSION_UPLOAD = "/api/submission/upload"
    val API_SUBMISSION_SUBMIT = "/api/submission/submit"
    val API_SUBMISSION_AUTHORS_ADD = "/api/submission/authors/add"
    val API_SUBMISSION_AUTHORS_REMOVE = "/api/submission/authors/remove"
    val API_SUBMISSION_AFFILIATIONS_ADD = "/api/submission/affiliations/add"
    val API_SUBMISSION_AFFILIATIONS_REMOVE = "/api/submission/affiliations/remove"
    val API_SUBMISSION_ENTRIES_ADD = "/api/submission/entries/add"
    val API_SUBMISSION_ENTRIES_REMOVE = "/api/submission/entries/remove"
    val API_SUBMISSION_ENTRY_TOPICS_ADD = "/api/submission/entry/topics/add"
    val API_SUBMISSION_ENTRY_TOPICS_REMOVE = "/api/submission/entry/topics/remove"
    val API_SUBMISSION_ENTRY_AUTHORS_ADD = "/api/submission/entry/authors/add"
    val API_SUBMISSION_ENTRY_AUTHORS_REMOVE = "/api/submission/entry/authors/remove"
    val API_SUBMISSION_ENTRY_NOTIFIES_ADD = "/api/submission/entry/notifies/add"
    val API_SUBMISSION_ENTRY_NOTIFIES_REMOVE = "/api/submission/entry/notifies/remove"
    val API_SUBMISSION_ENTRY_RELATED_ADD = "/api/submission/entry/related/add"
    val API_SUBMISSION_ENTRY_RELATED_REMOVE = "/api/submission/entry/related/remove"


    /* fields */

    val ENTRY = "entry"
    val ID = "id"
    val ADDRESS = "address"
    val NAME = "name"
    val ORCID = "orcid"
    val TITLE = "title"
    val AUTHOR = "author"
    val NOTIFY = "notify"
    val AFFILIATION = "affiliation"
    val LICENSE = "license"
    val TOPIC = "topic"
    val ABSTRACT = "abstract"
    val MESSAGE = "message"
    val ARCHIVE = "archive"
    val RELATED = "related"
    val KIND = "kind"
    val INPUT = "input"


    /* utils */

    def keyed(id: String, value: String): String = "[" + id + "] " + value

    def author_string(author: Author): String = {
      val orcid =
        author.orcid.map(orcid => "(ORCID id: " + orcid.identifier + ")").getOrElse("")
      keyed(author.id, author.name) + orcid
    }

    def author_option(author: Author): XML.Elem = option(author.id, author_string(author))

    def affil_id(affil: Affiliation): String =
      affil match {
        case Unaffiliated(_) => ""
        case Email(_, id, _) => id
        case Homepage(_, id, _) => id
      }

    def affil_address(affil: Affiliation): String =
      affil match {
        case Unaffiliated(_) => ""
        case Email(_, _, address) => address
        case Homepage(_, _, url) => url.toString
      }

    def affil_string(affil: Affiliation): String =
      affil match {
        case Unaffiliated(_) => "No affiliation"
        case Email(_, id, address) => keyed(id, address)
        case Homepage(_, id, url) => keyed(id, url.toString)
      }

    def related_string(related: Reference): String = related match {
      case Metadata.DOI(identifier) => identifier
      case Metadata.Formatted(rep) => rep
    }

    def indexed[A, B](l: List[A], key: Params.Key, field: String, f: (A, Params.Key) => B) =
      l.zipWithIndex map {
        case (a, i) => f(a, List_Key(key, field, i))
      }

    def fold[A](it: List[Params.Data], a: A)(f: (Params.Data, A) => Option[A]): Option[A] =
      it.foldLeft(Option(a)) {
        case (None, _) => None
        case (Some(a), param) => f(param, a)
      }

    def render_if(cond: Boolean, elem: XML.Elem): XML.Body = if (cond) List(elem) else Nil
    def render_error(for_elem: String, validated: Validated[_]): XML.Body =
      validated.error.map(error =>
        break ::: List(css("color: red")(label(for_elem, error)))).getOrElse(Nil)


    /* view */

    def render_metadata(model: Model.Metadata): XML.Body = {
      val updated_authors = model.updated_authors(authors)
      val authors_list = updated_authors.values.toList.sortBy(_.id)
      val (entry_authors, other_authors) =
        updated_authors.values.toList.sortBy(_.id).partition(author =>
          model.used_authors.contains(author.id))
      val email_authors = authors_list.filter(_.emails.nonEmpty)

      def render_topic(topic: Topic, key: Params.Key): XML.Elem =
        par(
          hidden(Nest_Key(key, ID), topic.id) ::
          text(topic.id) :::
          action_button(API_SUBMISSION_ENTRY_TOPICS_REMOVE, "x", key) :: Nil)

      def render_affil(affil: Affiliation, key: Params.Key): XML.Elem = {
        val author = updated_authors(affil.author)
        val affils = author.emails ::: author.homepages ::: Unaffiliated(author.id) :: Nil
        par(
          hidden(Nest_Key(key, ID), affil.author) ::
            text(author_string(updated_authors(affil.author))) :::
            selection(Nest_Key(key, AFFILIATION),
              Some(affil_id(affil)),
              affils.map(affil => option(affil_id(affil), affil_string(affil)))) ::
            action_button(API_SUBMISSION_ENTRY_AUTHORS_REMOVE, "x", key) :: Nil)
      }

      def render_notify(email: Email, key: Params.Key): XML.Elem = {
        val author = updated_authors(email.author)
        par(
          hidden(Nest_Key(key, ID), email.author) ::
            text(author_string(updated_authors(email.author))) :::
            selection(
              Nest_Key(key, AFFILIATION),
              Some(affil_id(email)),
              author.emails.map(affil => option(affil_id(affil), affil_string(affil)))) ::
            action_button(API_SUBMISSION_ENTRY_NOTIFIES_REMOVE, "x", key) :: Nil)
      }

      def render_related(related: Reference, key: Params.Key): XML.Elem =
        par(
          hidden(Nest_Key(key, KIND), Model.Related.get(related).toString) ::
          hidden(Nest_Key(key, INPUT), related_string(related)) ::
          text(related_string(related)) :::
          action_button(API_SUBMISSION_ENTRY_RELATED_REMOVE, "x", key) :: Nil)

      def render_entry(entry: Model.Entry, key: Params.Key): XML.Elem =
        fieldset(List(legend("Entry"),
          par(
            fieldlabel(Nest_Key(key, TITLE), "Title of article") ::
            textfield(Nest_Key(key, TITLE), "Example Submission", entry.title.v) ::
            render_error(Nest_Key(key, TITLE), entry.title)),
          par(
            fieldlabel(Nest_Key(key, NAME), "Short name") ::
            textfield(Nest_Key(key, NAME), "Example_Submission", entry.name.v) ::
            explanation(Nest_Key(key, NAME),
              "Name of the folder where your ROOT and theory files reside.") ::
            render_error(Nest_Key(key, NAME), entry.name)),
          fieldset(legend("Topics") ::
            indexed(entry.topics.v, key, TOPIC, render_topic) :::
            selection(Nest_Key(key, TOPIC),
              entry.topic_input.v.map(_.id),
              topics.map(topic => option(topic.id, topic.id))) ::
            action_button(API_SUBMISSION_ENTRY_TOPICS_ADD, "add", key) ::
            render_error(Nest_Key(key, TOPIC), entry.topics)),
          par(List(
            fieldlabel(Nest_Key(key, LICENSE), "License"),
            radio(Nest_Key(key, LICENSE),
              entry.license.id,
              licenses.values.toList.map(license => license.id -> license.name)),
            explanation(Nest_Key(key, LICENSE),
              "Note: For LGPL submissions, please include the header \"License: LGPL\" in each file"))),
          par(
            fieldlabel(Nest_Key(key, ABSTRACT), "Abstract") ::
            placeholder("HTML and MathJax, no LaTeX")(
              textarea(Nest_Key(key, ABSTRACT), entry.`abstract`.v) +
                ("rows" -> "5") +
                ("cols" -> "70")) ::
            explanation(Nest_Key(key, ABSTRACT),
              "Note: You can use HTML or MathJax (not LaTeX!) to format your abstract.") ::
            render_error(Nest_Key(key, ABSTRACT), entry.`abstract`)),
          fieldset(legend("Authors") ::
            indexed(entry.affils.v, key, AUTHOR, render_affil) :::
            selection(Nest_Key(key, AUTHOR),
              entry.author_input.v.map(_.id),
              authors_list.map(author => option(author.id, author_string(author)))) ::
            action_button(API_SUBMISSION_ENTRY_AUTHORS_ADD, "add", key) ::
            explanation(Nest_Key(key, AUTHOR),
              "Add an author from the list. You can also:") ::
            link("#" + Nest_Key(AUTHOR, NAME), text("register new")) ::
            render_error(Nest_Key(key, AUTHOR), entry.author_input) :::
            render_error("", entry.affils)),
          fieldset(legend("Contact") ::
            indexed(entry.notifies.v, key, NOTIFY, render_notify) :::
            selection(Nest_Key(key, NOTIFY),
              entry.notify_input.v.map(_.id),
              optgroup("Suggested", email_authors.filter(author =>
                entry.used_authors.contains(author.id)).map(author_option)) ::
                email_authors.filter(author =>
                  !entry.used_authors.contains(author.id)).map(author_option)) ::
            action_button(API_SUBMISSION_ENTRY_NOTIFIES_ADD, "add", key) ::
            explanation(Nest_Key(key, NOTIFY),
              "These addresses serve two purposes: " +
              "1. They are used to send you updates about the state of your submission. " +
              "2. They are the maintainers of the entry once it is accepted. " +
              "Typically this will be one or more of the authors.") ::
            render_error(Nest_Key(key, NOTIFY), entry.notify_input) :::
            render_error("", entry.notifies)),
          fieldset(legend("Related Publications") ::
            indexed(entry.related, key, RELATED, render_related) :::
            selection(Nest_Key(Nest_Key(key, RELATED), KIND),
              entry.related_kind.map(_.toString),
              Model.Related.values.toList.map(v => option(v.toString, v.toString))) ::
            textfield(Nest_Key(Nest_Key(key, RELATED), INPUT),
              "10.1109/5.771073 or HTML", entry.related_input.v) ::
            action_button(API_SUBMISSION_ENTRY_RELATED_ADD, "add", key) ::
            explanation(Nest_Key(Nest_Key(key, RELATED), INPUT),
              "Publications related to the entry, as DOIs (10.1109/5.771073) or free text (HTML).") ::
            render_error(Nest_Key(Nest_Key(key, RELATED), INPUT), entry.related_input)),
          action_button(API_SUBMISSION_ENTRIES_REMOVE, "remove entry", key)))

      def render_new_author(author: Author, key: Params.Key): XML.Elem =
        par(
          hidden(Nest_Key(key, ID), author.id) ::
          hidden(Nest_Key(key, NAME), author.name) ::
          hidden(Nest_Key(key, ORCID), author.orcid.map(_.identifier).getOrElse("")) ::
          text(author_string(author)) :::
          render_if(!model.used_authors.contains(author.id),
            action_button(API_SUBMISSION_AUTHORS_REMOVE, "x", key)))

      def render_new_affil(affil: Affiliation, key: Params.Key): XML.Elem =
        par(
          hidden(Nest_Key(key, AUTHOR), affil.author) ::
          hidden(Nest_Key(key, ID), affil_id(affil)) ::
          hidden(Nest_Key(key, AFFILIATION), affil_address(affil)) ::
          text(author_string(updated_authors(affil.author)) + ": " + affil_string(affil)) :::
          render_if(!model.used_affils.contains(affil),
            action_button(API_SUBMISSION_AFFILIATIONS_REMOVE, "x", key)))

      indexed(model.entries.v, Params.empty, ENTRY, render_entry) :::
        render_error("", model.entries) :::
        par(List(
          explanation("",
            "You can submit multiple entries at once. " +
            "Put the corresponding folders in the archive " +
            "and use the button below to add more input fields for metadata. "),
          api_button(API_SUBMISSION_ENTRIES_ADD, "additional entry"))) ::
        fieldset(legend("New Authors") ::
          explanation("", "If you are new to the AFP, add yourself here.") ::
          indexed(model.new_authors.v, Params.empty, AUTHOR, render_new_author) :::
          fieldlabel(Nest_Key(AUTHOR, NAME), "Name") ::
          textfield(Nest_Key(AUTHOR, NAME), "Gerwin Klein", model.new_author_input.v) ::
          fieldlabel(Nest_Key(AUTHOR, ORCID), "ORCID id (optional)") ::
          textfield(Nest_Key(AUTHOR, ORCID), "0000-0002-1825-0097", model.new_author_orcid.v) ::
          api_button(API_SUBMISSION_AUTHORS_ADD, "add") ::
          render_error(Nest_Key(AUTHOR, NAME), model.new_author_input) :::
          render_error(Nest_Key(AUTHOR, ORCID), model.new_author_orcid) :::
          render_error("", model.new_authors)) ::
        fieldset(legend("New affiliations") ::
          explanation("",
            "Add new affiliations here. " +
            "If you would like to update an existing affiliation, " +
            "submit with the old one and write to the editors.") ::
          indexed(model.new_affils.v, Params.empty, AFFILIATION, render_new_affil) :::
          fieldlabel(AFFILIATION, "Author") ::
          selection(AFFILIATION,
            model.new_affils_author.map(_.id),
            optgroup("Entry authors", entry_authors.map(author_option)) ::
              other_authors.map(author_option)) ::
          fieldlabel(Nest_Key(AFFILIATION, ADDRESS), "Email or Homepage") ::
          textfield(Nest_Key(AFFILIATION, ADDRESS), "https://proofcraft.org",
            model.new_affils_input.v) ::
          api_button(API_SUBMISSION_AFFILIATIONS_ADD, "add") ::
          render_error(Nest_Key(AFFILIATION, ADDRESS), model.new_affils_input) :::
          render_error("", model.new_affils)) ::
        fieldset(List(legend("Upload"),
          fieldlabel(MESSAGE, "Message for the editors (optional)"),
          textfield(MESSAGE, "", model.message),
          explanation(MESSAGE,
            "Note: Anything special or noteworthy about your submission can be covered here. " +
            "It will not become part of your entry. " +
            "You're also welcome to leave suggestions for our AFP submission service here. "),
          api_button(API_SUBMISSION_UPLOAD, "upload archive >"))) :: Nil
    }

    def render_upload(upload: Model.Upload): XML.Body =
      List(
        css("display: none")(div(render_metadata(upload.metadata))),
        fieldset(legend("Submit") ::
          api_button(API_SUBMISSION, "< back") ::
          par(List(
            fieldlabel(ARCHIVE, "Archive file (.tar.gz or .zip)"),
            browse(ARCHIVE, List(".zip", ".tar.gz")),
            explanation(ARCHIVE,
              "Note: Your zip or tar file should contain one folder with your theories, ROOT, etc. " +
              "The folder name should be the short/folder name used in the submission form."))) ::
          api_button(API_SUBMISSION_SUBMIT, "submit") ::
          render_error(ARCHIVE, Validated.error((), upload.error))))

    def render(model: Model.T): XML.Body =
      model match {
        case metadata: Model.Metadata =>
          List(submit_form(API_SUBMISSION, render_metadata(metadata)))
        case upload: Model.Upload =>
          List(submit_form(API_SUBMISSION, render_upload(upload)))
        case Model.Submitted(id) =>
          text("Success!") ::: link("/submissions/" + id, text("view entry")) :: Nil
        case Model.Invalid =>
          text("Invalid request")
      }


    /* validation */

    def validate_topic(
      id: Topic.ID,
      selected: List[Topic]
    ): (Option[Topic], Validated[Option[Topic]]) = {
      topics.find(_.id == id) match {
        case Some(topic) =>
          if (selected.contains(topic))
            (None, Validated.error(Some(topic), "Topic already selected"))
          else (Some(topic), Validated.ok(None))
        case _ => (None, Validated.ok(None))
      }
    }

    def validate_new_author(
      id: Author.ID,
      name: String,
      orcid_str: String,
      authors: Map[Author.ID, Author]
    ): (Option[Author], Validated[String], Validated[String]) = {
      val Id = """[a-zA-Z][a-zA-Z0-9]*""".r
      id match {
        case Id() if !authors.contains(id) =>
          if (name.isEmpty || name.trim != name)
            (None, Validated.error(name, "Name must not be empty"), Validated.ok(orcid_str))
          else if (orcid_str.isEmpty)
            (Some(Author(id, name)), Validated.ok(""), Validated.ok(""))
          else Exn.capture(Orcid(orcid_str)) match {
            case Exn.Res(orcid) =>
              if (authors.values.exists(_.orcid.exists(_ == orcid)))
                (None, Validated.ok(name), Validated.error(orcid_str,
                  "Author with that orcid already exists"))
              else
                (Some(Author(id, name, orcid = Some(orcid))), Validated.ok(""), Validated.ok(""))
            case _ => (None, Validated.ok(name), Validated.error(orcid_str, "Invalid orcid"))
          }
        case _ => (None, Validated.ok(name), Validated.ok(orcid_str))
      }
    }

    def validate_new_affil(
      id: String,
      address: String,
      author: Author
    ): (Option[Affiliation], Validated[String]) = {
      if (address.isBlank) (None, Validated.error(address, "Address must not be empty"))
      else if (address.contains("@")) {
        val Id = (author.id + """_email\d*""").r
        id match {
          case Id() if !author.emails.map(_.id).contains(id) =>
            val Address = """([^@\s]+)@([^@\s]+)""".r
            address match {
              case Address(user, host) => (Some(Email(author.id, id, user, host)), Validated.ok(""))
              case _ => (None, Validated.error(address, "Invalid email address"))
            }
          case _ => (None, Validated.ok(""))
        }
      }
      else {
        val Id = (author.id + """_homepage\d*""").r
        id match {
          case Id() if !author.homepages.map(_.id).contains(id) =>
            Exn.capture(new URL(address)) match {
              case Exn.Res(url) => (Some(Homepage(author.id, id, url)), Validated.ok(""))
              case _ => (None, Validated.error(address, "Invalid url"))
            }
          case _ => (None, Validated.ok(""))
        }
      }
    }

    def validate_related(
      kind: Model.Related.Value,
      related: String,
      references: List[Reference]
    ): (Option[Reference], Validated[String]) =
      if (related.isBlank) (None, Validated.error(related, "Reference must not be empty"))
      else {
        kind match {
          case Model.Related.DOI =>
            Exn.capture(DOI(related)) match {
              case Exn.Res(doi) =>
                if (references.contains(doi)) (None, Validated.error(related, "Already present"))
                else (Some(doi), Validated.ok(""))
              case _ => (None, Validated.error(related, "Invalid DOI format"))
            }
          case Model.Related.Formatted =>
            val formatted = Formatted(related)
            if (references.contains(formatted)) (None, Validated.error(related, "Already present"))
            else (Some(formatted), Validated.ok(""))
        }
      }


    /* param parsing */

    def parse_metadata(params: Params.Data): Option[Model.Metadata] = {
      def parse_topic(topic: Params.Data, topics: List[Topic]): Option[Topic] =
        validate_topic(topic.get(ID).value, topics)._1

      def parse_email(email: Params.Data, authors: Map[Author.ID, Author]): Option[Email] =
        authors.get(email.get(ID).value).flatMap(
          _.emails.find(_.id == email.get(AFFILIATION).value))

      def parse_affil(affil: Params.Data, authors: Map[Author.ID, Author]): Option[Affiliation] =
        authors.get(affil.get(ID).value).flatMap { author =>
          val id = affil.get(AFFILIATION).value
          if (id.isEmpty) Some(Unaffiliated(author.id))
          else (author.emails ++ author.homepages).collectFirst {
            case e: Email if e.id == id => e
            case h: Homepage if h.id == id => h
          }
        }

      def parse_related(related: Params.Data, references: List[Reference]): Option[Reference] =
        Model.Related.from_string(related.get(KIND).value).flatMap(
          validate_related(_, related.get(INPUT).value, references)._1)

      def parse_new_author(author: Params.Data, authors: Map[Author.ID, Author]): Option[Author] =
        validate_new_author(
          author.get(ID).value, author.get(NAME).value, author.get(ORCID).value, authors)._1

      def parse_new_affil(affil: Params.Data, authors: Map[Author.ID, Author]): Option[Affiliation] =
        authors.get(affil.get(AUTHOR).value).flatMap(author =>
          validate_new_affil(affil.get(ID).value, affil.get(AFFILIATION).value, author)._1)

      def parse_entry(entry: Params.Data, authors: Map[Author.ID, Author]): Option[Model.Entry] =
        for {
          topics <-
            fold(entry.list(TOPIC), List.empty[Topic]) {
              case (topic, topics) => parse_topic(topic, topics).map(topics :+ _)
            }
          affils <-
            fold(entry.list(AUTHOR), List.empty[Affiliation]) {
              case (affil, affils) => parse_affil(affil, authors).map(affils :+ _)
            }
          notifies <-
            fold(entry.list(NOTIFY), List.empty[Email]) {
              case (email, emails) => parse_email(email, authors).map(emails :+ _)
            }
          related <-
            fold(entry.list(RELATED), List.empty[Reference]) {
              case (related, references) => parse_related(related, references).map(references :+ _)
            }
          license <- licenses.get(entry.get(LICENSE).value)
        } yield Model.Entry(
          name = Validated.ok(entry.get(NAME).value),
          title = Validated.ok(entry.get(TITLE).value),
          topics = Validated.ok(topics),
          topic_input = Validated.ok(parse_topic(entry.get(TOPIC), Nil)),
          license = license,
          `abstract` = Validated.ok(entry.get(ABSTRACT).value),
          author_input = Validated.ok(authors.get(entry.get(AUTHOR).value)),
          notify_input = Validated.ok(authors.get(entry.get(NOTIFY).value)),
          affils = Validated.ok(affils),
          notifies = Validated.ok(notifies),
          related = related,
          related_kind = Model.Related.from_string(entry.get(RELATED).get(KIND).value),
          related_input = Validated.ok(entry.get(RELATED).get(INPUT).value))

      for {
        (new_author_ids, all_authors) <-
          fold(params.list(AUTHOR), (List.empty[Author.ID], authors)) {
            case (author, (new_authors, authors)) =>
              parse_new_author(author, authors).map(author =>
                (new_authors :+ author.id, authors.updated(author.id, author)))
          }
        (new_affils, all_authors) <-
          fold(params.list(AFFILIATION), (List.empty[Affiliation], all_authors)) {
            case (affil, (new_affils, authors)) =>
              parse_new_affil(affil, authors).map { affil =>
                val author = authors(affil.author)
                (new_affils :+ affil, affil match {
                  case _: Unaffiliated => authors
                  case e: Email =>
                    authors.updated(author.id, author.copy(emails = author.emails :+ e))
                  case h: Homepage =>
                    authors.updated(author.id, author.copy(homepages = author.homepages :+ h))
                })
              }
            }
        new_authors = new_author_ids.map(all_authors)
        entries <- fold(params.list(ENTRY), List.empty[Model.Entry]) {
          case (entry, entries) => parse_entry(entry, all_authors).map(entries :+ _)
        }
      } yield Model.Metadata(
        entries = Validated.ok(entries),
        new_authors = Validated.ok(new_authors),
        new_author_input = Validated.ok(params.get(AUTHOR).get(NAME).value),
        new_author_orcid = Validated.ok(params.get(AUTHOR).get(ORCID).value),
        new_affils = Validated.ok(new_affils),
        new_affils_author = all_authors.get(params.get(AFFILIATION).value),
        new_affils_input = Validated.ok(params.get(AFFILIATION).get(ADDRESS).value),
        message = params.get(MESSAGE).value)
    }


    /* control */

    def add_entry(params: Params.Data): Option[Model.T] = parse_metadata(params).map { model =>
      model.copy(entries = Validated.ok(model.entries.v :+ Model.Entry(license = licenses.head._2)))
    }

    def remove_entry(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        num_entry <- List_Key.num(ENTRY, params.get(Web_App.ACTION).value)
      } yield model.copy(entries = Validated.ok(Utils.remove_at(num_entry, model.entries.v)))

    def add_author(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        num_entry <- List_Key.num(ENTRY, params.get(Web_App.ACTION).value)
        entry <- model.entries.v.unapply(num_entry)
        author <- entry.author_input.v
      } yield {
        val default_affil = author.emails.headOption.orElse(
          author.homepages.headOption).getOrElse(Unaffiliated(author.id))

        model.update_entry(num_entry, entry.copy(
          author_input = Validated.ok(None),
          affils = Validated.ok(entry.affils.v :+ default_affil)))
      }

    def remove_author(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        (action, num_affil) <- List_Key.split(AUTHOR, params.get(Web_App.ACTION).value)
        num_entry <- List_Key.num(ENTRY, action)
        entry <- model.entries.v.unapply(num_entry)
      } yield
        model.update_entry(num_entry, entry.copy(affils =
          Validated.ok(Utils.remove_at(num_affil, entry.affils.v))))

    def add_notify(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        num_entry <- List_Key.num(ENTRY, params.get(Web_App.ACTION).value)
        entry <- model.entries.v.unapply(num_entry)
        author <- entry.notify_input.v
        email <- author.emails.headOption
      } yield
        model.update_entry(num_entry, entry.copy(notify_input =
          Validated.ok(None), notifies = Validated.ok(entry.notifies.v :+ email)))

    def remove_notify(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        (action, num_notify) <- List_Key.split(NOTIFY, params.get(Web_App.ACTION).value)
        num_entry <- List_Key.num(ENTRY, action)
        entry <- model.entries.v.unapply(num_entry)
      } yield
        model.update_entry(num_entry, entry.copy(notifies =
          Validated.ok(Utils.remove_at(num_notify, entry.notifies.v))))

    def add_topic(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        num_entry <- List_Key.num(ENTRY, params.get(Web_App.ACTION).value)
        entry <- model.entries.v.unapply(num_entry)
        entry_params <- params.list(ENTRY).unapply(num_entry)
      } yield {
        val (topic, topic_input) = validate_topic(entry_params.get(TOPIC).value, entry.topics.v)
        model.update_entry(num_entry, entry.copy(topic_input = topic_input,
          topics = Validated.ok(entry.topics.v ++ topic)))
      }

    def remove_topic(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        (action, num_topic) <- List_Key.split(TOPIC, params.get(Web_App.ACTION).value)
        num_entry <- List_Key.num(ENTRY, action)
        entry <- model.entries.v.unapply(num_entry)
      } yield {
        val entry1 = entry.copy(topics = Validated.ok(Utils.remove_at(num_topic, entry.topics.v)))
        model.copy(entries = Validated.ok(model.entries.v.updated(num_entry, entry1)))
      }

    def add_related(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        num_entry <- List_Key.num(ENTRY, params.get(Web_App.ACTION).value)
        entry <- model.entries.v.unapply(num_entry)
        kind <- entry.related_kind
      } yield {
        val (reference, related_input) =
          validate_related(kind, entry.related_input.v, entry.related)
        model.update_entry(num_entry, entry.copy(related = entry.related ++ reference,
          related_input = related_input))
      }

    def remove_related(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        (action, num_related) <- List_Key.split(RELATED, params.get(Web_App.ACTION).value)
        num_entry <- List_Key.num(ENTRY, action)
        entry <- model.entries.v.unapply(num_entry)
      } yield {
        val entry1 = entry.copy(related = Utils.remove_at(num_related, entry.related))
        model.copy(entries = Validated.ok(model.entries.v.updated(num_entry, entry1)))
      }

    def add_new_author(params: Params.Data): Option[Model.T] = parse_metadata(params).map { model =>
      val name = model.new_author_input.v.trim
      if (name.isEmpty)
        model.copy(new_author_input = model.new_author_input.with_error("Name must not be empty"))
      else {
        def as_ascii(str: String) = {
          var res: String = str
          List("ö" -> "oe", "ü" -> "ue", "ä" -> "ae", "ß" -> "ss").foreach {
            case (c, rep) => res = res.replace(c, rep)
          }
          Normalizer.normalize(res, Normalizer.Form.NFD).replaceAll("[^\\x00-\\x7F]", "")
        }

        def make_author_id(name: String): String = {
          val normalized = as_ascii(name)
          val Suffix = """^.*?([a-zA-Z]*)$""".r
          val suffix = normalized match {
            case Suffix(suffix) => suffix
            case _ => ""
          }
          val Prefix = """^([a-zA-Z]*).*$""".r
          val prefix = normalized.stripSuffix(suffix) match {
            case Prefix(prefix) => prefix
            case _ => ""
          }
          var ident = suffix.toLowerCase
          for (c <- prefix.toLowerCase) {
            if (model.updated_authors(authors).contains(ident)) ident += c.toString
            else return ident
          }
          Utils.make_unique(ident, model.updated_authors(authors).keySet)
        }

        val id = make_author_id(name)

        val (author, new_author_input, new_author_orcid) = validate_new_author(id,
          model.new_author_input.v, model.new_author_orcid.v, model.updated_authors(authors))

        model.copy(
          new_author_input = new_author_input, new_author_orcid = new_author_orcid,
          new_authors = Validated.ok(model.new_authors.v ++ author))
      }
    }

    def remove_new_author(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        num_author <- List_Key.num(AUTHOR, params.get(Web_App.ACTION).value)
        author <- model.new_authors.v.unapply(num_author)
        if !model.used_authors.contains(author.id)
      } yield model.copy(new_authors =
        Validated.ok(Utils.remove_at(num_author, model.new_authors.v)))

    def add_new_affil(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        author <- model.new_affils_author
      } yield {
        val address = model.new_affils_input.v.trim
        if (address.isEmpty)
          model.copy(new_affils_input = model.new_affils_input.with_error("Must not be empty"))
        else {
          val id =
            if (address.contains("@"))
              Utils.make_unique(author.id + "_email", author.emails.map(_.id).toSet)
            else
              Utils.make_unique(author.id + "_homepage", author.homepages.map(_.id).toSet)

          val (affil, new_affils_input) = validate_new_affil(id, address, author)
          model.copy(new_affils = Validated.ok(model.new_affils.v ++ affil), new_affils_input =
            new_affils_input)
        }
      }

    def remove_new_affil(params: Params.Data): Option[Model.T] =
      for {
        model <- parse_metadata(params)
        num_affil <- List_Key.num(AFFILIATION, params.get(Web_App.ACTION).value)
        affil <- model.new_affils.v.unapply(num_affil)
        if !model.used_affils.contains(affil)
      } yield model.copy(new_affils = Validated.ok(Utils.remove_at(num_affil, model.new_affils.v)))

    def upload(params: Params.Data): Option[Model.T] = parse_metadata(params).map { model =>
      var ok = true

      def validate[A](validator: A => Validated[A], value: A): Validated[A] = {
        val res = validator(value)
        if (res.error.nonEmpty) ok = false
        res
      }

      def title(title: String): Validated[String] =
        if (title.isBlank) Validated.error(title, "Title must not be blank")
        else if (title.trim != title) Validated.error(title, "Title must not contain extra spaces")
        else Validated.ok(title)

      def name(name: String): Validated[String] =
        if (name.isBlank) Validated.error(name, "Name must not be blank")
        else if (!"[a-zA-Z0-9_-]+".r.matches(name)) Validated.error(name,
          "Invalid character in name")
        else if (Server.this.entries.contains(name)) Validated.error(name, "Entry already exists")
        else Validated.ok(name)

      def entries(entries: List[Model.Entry]): Validated[List[Model.Entry]] =
        if (entries.isEmpty) Validated.error(entries, "Must contain at least one entry")
        else if (Library.duplicates(entries.map(_.name)).nonEmpty)
          Validated.error(entries, "Entries must have different names")
        else Validated.ok(entries)

      def new_authors(authors: List[Author]): Validated[List[Author]] =
        if (!authors.forall(author => model.used_authors.contains(author.id)))
          Validated.error(authors, "Unused authors")
        else Validated.ok(authors)

      def new_affils(affils: List[Affiliation]): Validated[List[Affiliation]] =
        if (!affils.forall(affil => model.used_affils.contains(affil)))
          Validated.error(affils, "Unused affils")
        else Validated.ok(affils)

      def authors(authors: List[Affiliation]): Validated[List[Affiliation]] =
        if (authors.isEmpty) Validated.error(authors, "Must contain at least one author")
        else if (!Utils.is_distinct(authors)) Validated.error(authors, "Duplicate affiliations")
        else Validated.ok(authors)

      def notifies(notifies: List[Email]): Validated[List[Email]] =
        if (notifies.isEmpty) Validated.error(notifies, "Must contain at least one maintainer")
        else if (!Utils.is_distinct(notifies)) Validated.error(notifies, "Duplicate emails")
        else Validated.ok(notifies)

      def topics(topics: List[Topic]): Validated[List[Topic]] =
        if (topics.isEmpty) Validated.error(topics, "Must contain at least one topic")
        else Validated.ok(topics)

      def `abstract`(txt: String): Validated[String] =
        if (txt.isBlank) Validated.error(txt, "Entry must contain an abstract")
        else if (List("\\cite", "\\emph", "\\texttt").exists(txt.contains(_)))
          Validated.error(txt, "LaTeX not allowed, use MathJax for math symbols")
        else Validated.ok(txt)

      val validated = model.copy(
        entries = validate(
          entries, model.entries.v.map(entry => entry.copy(
            name = validate(name, entry.name.v),
            title = validate(title, entry.title.v),
            affils = validate(authors, entry.affils.v),
            notifies = validate(notifies, entry.notifies.v),
            topics = validate(topics, entry.topics.v),
            `abstract` = validate(`abstract`, entry.`abstract`.v)
          ))),
        new_authors = validate(new_authors, model.new_authors.v),
        new_affils = validate(new_affils, model.new_affils.v))

      if (ok) Model.Upload(validated, "") else validated
    }

    def submit(params: Params.Data): Option[Model.T] = {
      upload(params) match {
        case Some(Model.Upload(model, _)) =>
          val archive = Bytes.decode_base64(params.get(ARCHIVE).get(FILE).value)
          val file_name = params.get(ARCHIVE).value
          if (archive.is_empty || file_name.isEmpty) {
            Some(Model.Upload(model, "Select a file"))
          } else if (!file_name.endsWith(".zip") && !file_name.endsWith(".tar.gz")) {
            None
          } else {
            val id = Date.now().format(Date.Format("dd-MM-uuuu_HH-mm-ss_SSS"))
            val dir = submission_dir + Path.basic(id)
            dir.file.mkdirs()

            val archive_name =
              if (file_name.endsWith(".zip")) "archive.zip" else "archive.tar.gz"
            Bytes.write(dir + Path.basic(archive_name), archive)

            val submission = AFP_Structure(dir)

            val entries = model.entries.v.map(entry =>
              Metadata.Entry(
                name = entry.name.v,
                title = entry.title.v,
                authors = entry.affils.v,
                date = LocalDate.now(),
                topics = entry.topics.v,
                `abstract` = entry.`abstract`.v.trim,
                notifies = entry.notifies.v,
                license = entry.license,
                note = "",
                related = entry.related))

            entries.foreach(submission.save_entry)
            submission.save_authors(model.updated_authors(authors).values.toList)

            Some(Model.Submitted(id))
          }
        case _ => None
      }
    }

    val submit_init =
      Model.Metadata(entries = Validated.ok(List(Model.Entry(license = licenses.head._2))))
    val error = Model.Invalid

    val endpoints = List(
      Get(SUBMIT, "get submit", submit_init),
      Post(API_SUBMISSION, "get", parse_metadata),
      Post(API_SUBMISSION_AUTHORS_ADD, "add author",  add_new_author),
      Post(API_SUBMISSION_AUTHORS_REMOVE, "remove author", remove_new_author),
      Post(API_SUBMISSION_AFFILIATIONS_ADD, "add affil", add_new_affil),
      Post(API_SUBMISSION_AFFILIATIONS_REMOVE, "remove affil", remove_new_affil),
      Post(API_SUBMISSION_ENTRIES_ADD, "add entry", add_entry),
      Post(API_SUBMISSION_ENTRIES_REMOVE, "remove entry", remove_entry),
      Post(API_SUBMISSION_ENTRY_AUTHORS_ADD, "add entry author", add_author),
      Post(API_SUBMISSION_ENTRY_AUTHORS_REMOVE, "remove entry author", remove_author),
      Post(API_SUBMISSION_ENTRY_NOTIFIES_ADD, "add notify", add_notify),
      Post(API_SUBMISSION_ENTRY_NOTIFIES_REMOVE, "remove notify", remove_notify),
      Post(API_SUBMISSION_ENTRY_TOPICS_ADD, "add topic", add_topic),
      Post(API_SUBMISSION_ENTRY_TOPICS_REMOVE, "remove topic", remove_topic),
      Post(API_SUBMISSION_ENTRY_RELATED_ADD, "add related", add_related),
      Post(API_SUBMISSION_ENTRY_RELATED_REMOVE, "remove related", remove_related),
      Post(API_SUBMISSION_UPLOAD, "upload", upload),
      Post(API_SUBMISSION_SUBMIT, "submit", submit))
  }


  /* Isabelle tool wrapper */

  val isabelle_tool = Isabelle_Tool("afp_submit", "start afp submission server",
    Scala_Project.here,
    { args =>

      var verbose = false
      var port = 0

      val getopts = Getopts("""
Usage: isabelle afp_submit [OPTIONS] DIR

  Options are:
      -p PORT      server port. Default: """ + port + """
      -v           verbose

  Start afp submission server, which stores submissions in DIR.
""",
        "p:" -> (arg => port = Value.Int.parse(arg)),
        "v" -> (_ => verbose = true))

      val more_args = getopts(args)

      val dir =
        more_args match {
          case dir :: Nil => Path.explode(dir)
          case _ => getopts.usage()
        }

      val afp_structure = AFP_Structure()

      val progress = new Console_Progress(verbose = verbose)

      val authors = afp_structure.load_authors.map(author => author.id -> author).toMap
      val root_topics = afp_structure.load_topics.flatMap(_.all_topics)
      val licenses = afp_structure.load_licenses.map(license => license.id -> license).toMap
      val entries = afp_structure.entries.toSet

      val server = new Server(authors = authors, topics = root_topics, licenses = licenses,
        entries = entries, verbose = verbose, progress = progress, port = port,
        submission_dir = dir)

      server.run()
    })
}
