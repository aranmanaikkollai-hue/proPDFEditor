# PDF corpus for the text-editability analyzer

Put sample PDFs in this folder (commit them), then run the workflow **PDF text-editability analyzer (manual)**
(Actions tab > that workflow > Run workflow). The result is printed in the log and saved as the artifact
`editability-report` (`editability.txt`, `editability.json`).

* Only copy PDFs you are happy to store in the repository. The report contains file names, font names, counts and
  flags. It never contains the text of a document.
* 20-50 files from different producers (Word, LibreOffice, Chrome print-to-PDF, scanners, invoices, forms, e-books)
  give a useful picture. More is better.
* The report ends with a SUMMARY: how many fonts are not embedded, embedded in full, or embedded as subsets, and how
  many letters and digits a subset can still draw. That decides how often editing existing text could work.
