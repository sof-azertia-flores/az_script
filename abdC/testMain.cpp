
#include "library.h"
#include "azertianBinaryDataValues.h"
#include <iostream>
 using namespace std;
using namespace azertian;
union DoubleBytesConverter {
 double d;
 unsigned char bytes[sizeof(double)];
};
union Int2Bytes {
 int d;
 unsigned char bytes[sizeof(int)];
};
void printbytes(unsigned char * bytes,int size) {
 for (int i=0;i<size;i++) {
  cout<<(unsigned int)(bytes[i])<<" ";
 }
 cout<<endl;
}
class testklass {
 public:
 testklass() {
  cout<<"testklass constructed"<<endl;
 }
 ~testklass() {
  cout<<"testklass deconstructed"<<endl;
 }
};
void testr(shared_ptr<testklass> t) {
 cout<<"tCount:"<<t.use_count()<<endl;
}
void testre(shared_ptr<testklass>& t) {
 cout<<"tCount2:"<<t.use_count()<<endl;
}
int main() {
 auto ero=make_shared<AbdMap>();
 ero->put("testAsmr",make_shared<DoubleAbdValue>(1.28));
 printbytes(ero->toAbdValue()->toBytes().get(),ero->toAbdValue()->size+4);
 return 0;
//  auto * o =new unsigned char[4];
//  azertian::i2b(114514,o);
//  printf("%d %d %d %d\n",o[0],o[1],o[2],o[3]);
//  auto * as=new AbdStack();
//
//  unsigned char ds[5]={1,2,3,4,5};
//  as->vs.push_back(shared_ptr<AbdValue>(new AbdValue(ds,5)));
//  as->vs.push_back(shared_ptr<AbdValue>(new AbdValue(ds,5)));
//  as->vs.push_back(shared_ptr<AbdValue>(new AbdValue(ds,5)));
//  auto r=as->toAbdValue();
//  printbytes(r->toBytes().get(),(r->size)+4);
//  auto dso=make_shared<AbdStack>(r);
//  printbytes(
//  dso->vs[0].get()->data,5);
//  printbytes(
//  dso->vs[2].get()->data,5);
//  printbytes(
//  dso->vs[1].get()->data,5);
//  printbytes(dso->toAbdValue()->toBytes().get(),(r->size)+4);
//  {
//   auto * sav=new StringAbdValue("hello,world,你好");
//   printbytes(sav->toAbdValue()->toBytes().get(),sav->toAbdValue()->size+4);
//  }
//  auto * des=new AbdMap();
//  des->values.push_back(pair<string,shared_ptr<AbdMapValue>>(string("hello,world"),make_shared<StringAbdValue>("hello,world")));
//  des->values.push_back(pair<string,shared_ptr<AbdMapValue>>(string("hola"),make_shared<StringAbdValue>("hello,world")));
//  des->values.push_back(pair<string,shared_ptr<AbdMapValue>>(string("meow"),make_shared<StringAbdValue>("hello,world")));
//  auto rms=des->toAbdValue();
//  auto * res=new AbdMap(make_shared< AbdStack>(shared_ptr(rms)));
//  printbytes(rms->toBytes().get(),(rms->size)+4);
//  cout<<res->values[0].first<<endl;
}