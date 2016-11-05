
function clearHead() {
	while(document.head.firstChild) {
		document.head.removeChild(document.head.firstChild);
	}
}

function addStylesheet(href) {
	var fref = document.createElement("link");
	fref.setAttribute("rel", "stylesheet");
	fref.setAttribute("type", "text/css");
	fref.setAttribute("href", href);
	document.head.appendChild(fref);
}

document.body.innerHTML = "<div id='app'></div>";
clearHead();
addStylesheet("https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.3.5/css/bootstrap.css");
addStylesheet("https://res.cloudinary.com/mitchdzugan/raw/upload/v1477459061/re-com_rrfpmh.css");

jref = document.createElement("script");
jref.setAttribute("type", "text/javascript");
jref.setAttribute("src", "https://localhost:8080/ext.js");
document.head.appendChild(jref);
