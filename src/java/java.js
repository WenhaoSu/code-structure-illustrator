let width = 730;
let radius = width / 2;
let colorNone = "#ccc";
let colorOut = "#f00";
let colorIn = "#00f";

let tree = d3.cluster()
    .size([2 * Math.PI, radius - 100]);

let line = d3.lineRadial()
    .curve(d3.curveBundle.beta(0.85))
    .radius(d => d.y)
    .angle(d => d.x);

let data = [];


function id(node) {
    return `${node.parent ? id(node.parent) + "." : ""}${node.data.name}`;
}

function hierarchy(data, delimiter = ".") {
    let root;
    const map = new Map;
    data.forEach(function find(data) {
        console.log(data);
        const {name} = data;
        if (map.has(name)) return map.get(name);
        const i = name.lastIndexOf(delimiter);
        console.log( name.substring(0, i));
        map.set(name, data);
        if (i >= 0) {
            find({name: name.substring(0, i), children: []}).children.push(data);
            data.name = name.substring(i + 1);
        } else {
            root = data;
        }
        return data;
    });
    console.log(map);
    return root;
}

function bilink(root) {
    const map = new Map(root.leaves().map(d => [id(d), d]));
    for (const d of root.leaves()) d.incoming = [], d.outgoing = d.data.imports.map(i => [d, map.get(i)]);
    for (const d of root.leaves()) for (const o of d.outgoing) o[1].incoming.push(o);
    return root;
}

function asyncGetFile(url) {
    console.log("Getting text file");
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open("GET", url);
        xhr.onload = () => resolve(xhr.responseText);
        xhr.onerror = () => reject(xhr.statusText);
        xhr.send();
        console.log("Made promise");
    });
}

window.onload = function () {
    document.getElementById('input-file')
        .addEventListener('change', getFile);

};


function generateTree(data) {
    const root = tree(bilink(d3.hierarchy(data)
        .sort((a, b) => d3.ascending(a.height, b.height) || d3.ascending(a.data.name, b.data.name))));

    d3.select("body").select("#graph").select("svg").remove();

    let svg = d3.select("body").select("#graph").append("svg")
        .attr("viewBox", [-width / 2, -width / 2, width, width]);

    const node = svg.append("g")
        .attr("font-family", "sans-serif")
        .attr("font-size", 10)
        .selectAll("g")
        .data(root.leaves())
        .join("g")
        .attr("transform", d => `rotate(${d.x * 180 / Math.PI - 90}) translate(${d.y},0)`)
        .append("text")
        .attr("dy", "0.31em")
        .attr("x", d => d.x < Math.PI ? 6 : -6)
        .attr("text-anchor", d => d.x < Math.PI ? "start" : "end")
        .attr("transform", d => d.x >= Math.PI ? "rotate(180)" : null)
        .text(d => d.data.name)
        .each(function (d) {
            d.text = this;
        })
        .on("mouseover", overed)
        .on("mouseout", outed)
        .call(text => text.append("title").text(d => `${id(d)}
${d.outgoing.length} outgoing
${d.incoming.length} incoming`));

    const link = svg.append("g")
        .attr("stroke", colorNone)
        .attr("fill", "none")
        .selectAll("path")
        .data(root.leaves().flatMap(leaf => leaf.outgoing))
        .join("path")
        .style("mix-blend-mode", "multiply")
        .attr("d", ([i, o]) => line(i.path(o)))
        .each(function (d) {
            d.path = this;
        });

    function overed(d) {
        link.style("mix-blend-mode", null);
        d3.select(this).attr("font-weight", "bold");
        d3.selectAll(d.incoming.map(d => d.path)).attr("stroke", colorIn).raise();
        d3.selectAll(d.incoming.map(([d]) => d.text)).attr("fill", colorIn).attr("font-weight", "bold");
        d3.selectAll(d.outgoing.map(d => d.path)).attr("stroke", colorOut).raise();
        d3.selectAll(d.outgoing.map(([, d]) => d.text)).attr("fill", colorOut).attr("font-weight", "bold");
    }

    function outed(d) {
        link.style("mix-blend-mode", "multiply");
        d3.select(this).attr("font-weight", null);
        d3.selectAll(d.incoming.map(d => d.path)).attr("stroke", null);
        d3.selectAll(d.incoming.map(([d]) => d.text)).attr("fill", null).attr("font-weight", null);
        d3.selectAll(d.outgoing.map(d => d.path)).attr("stroke", null);
        d3.selectAll(d.outgoing.map(([, d]) => d.text)).attr("fill", null).attr("font-weight", null);
    }
}



// read file
function getFile(event) {
    const input = event.target;
    if ('files' in input && input.files.length > 0) {
        placeFileContent(
            input.files[0]);
    }
}

function placeFileContent(file) {
    readFileContent(file).then(content => {
        console.log(content);
        realParseJava(content);
        generateTree(hierarchy(data));
    }).catch(error => console.log(error));
}

function readFileContent(file) {
    const reader = new FileReader();
    return new Promise((resolve, reject) => {
        reader.onload = event => resolve(event.target.result);
        reader.onerror = error => reject(error);
        reader.readAsText(file);
    })
}




function realParseJava(content) {
    //TODO: Analyze the JSON cst
    const {parse} = require("java-parser");
    const javaText = `
public class Main{
  private static void test(int a){
    return;
  }
  
  public static void main(String args[]){
    test(a);
    System.out.println("Hello World !");
  }
}
`;


    const cst = parse(content);
    const {JSONPath} = require('jsonpath-plus');
    let methodNames = JSONPath({path: '$..methodDeclaration[0].children.methodHeader[0].children.methodDeclarator[0].children.Identifier[0].image', json: cst});
    let methods = JSONPath({path: '$..methodDeclaration[0].children', json:cst});
    methods.forEach(function(method){
        let namePath = '$.methodHeader[0].children.methodDeclarator[0].children.Identifier[0].image';
        let name = JSONPath({path:namePath, json:method})[0];
        let usePath = '$.methodBody[0].children..fqnOrRefTypePart[0].children.Identifier[0].image';
        let allUsage = JSONPath({path:usePath, json:method});
        let usage = allUsage.filter(function(elt){
            return methodNames.includes(elt);
        });
        for (let i = 0; i < usage.length; i++)
        {
            usage[i] = 'javaDraw.' + usage[i];
        }

        data.push({name: 'javaDraw.' + name, imports: usage});
    });
}
