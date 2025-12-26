const INITIAL_CUSTOMER_DEBTS = {
  '202200778': { name: 'შპს წისქვილი ჯგუფი', debt: 6740, date: '2025-04-29' },
  '53001051654': { name: 'ელგუჯა ციბაძე', debt: 141, date: '2025-04-29' },
  '431441843': { name: 'შპს მესი 2022', debt: 932, date: '2025-04-29' },
  '406146371': { name: 'შპს სიმბა 2015', debt: 7867, date: '2025-04-29' },
  '405640098': { name: 'შპს სქულფუდ', debt: 0, date: '2025-04-29' },
  '01008037949': { name: 'ირინე ხუნდაძე', debt: 1286, date: '2025-04-29' },
  '405135946': { name: 'შპს მაგსი', debt: 8009, date: '2025-04-29' },
  '402297787': { name: 'შპს ასი-100', debt: 9205, date: '2025-04-29' },
  '204900358': { name: 'შპს ვარაზის ხევი 95', debt: 0, date: '2025-04-29' },
  '405313209': { name: 'შპს  ხინკლის ფაბრიკა', debt: 2494, date: '2025-04-29' },
  '405452567': { name: 'შპს სამიკიტნო-მაჭახელა', debt: 6275, date: '2025-04-29' },
  '405138603': { name: 'შპს რესტორან მენეჯმენტ კომპანი', debt: 840, date: '2025-04-29' },
  '404851255': { name: 'შპს თაღლაურა  მენეჯმენტ კომპანი', debt: 3010, date: '2025-04-29' },
  '405226973': { name: 'შპს  ნარნია', debt: 126, date: '2025-04-29' },
  '405604190': { name: 'შპს ბუკა202', debt: 2961, date: '2025-04-29' },
  '405740417': { name: 'შპს მუჭა მუჭა 2024', debt: 3873, date: '2025-04-29' },
  '405587949': { name: 'შპს აკიდო 2023', debt: 1947, date: '2025-04-29' },
  '404869585': { name: 'შპს MASURO', debt: 1427, date: '2025-04-29' },
  '404401036': { name: 'შპს MSR', debt: 4248, date: '2025-04-29' },
  '01008057492': { name: 'ნინო მუშკუდიანი', debt: 3473, date: '2025-04-29' },
  '405379442': { name: 'შპს ქალაქი 27', debt: 354, date: '2025-04-29' },
  '205066845': { name: 'შპს "სპრინგი" -რესტორანი ბეღელი', debt: 3637, date: '2025-04-29' },
  '405270987': { name: 'შპს ნეკაფე', debt: 3801, date: '2025-04-29' },
  '405309884': { name: 'შპს თეისთი', debt: 0, date: '2025-04-29' },
  '404705440': { name: 'შპს იმფერი', debt: 773, date: '2025-04-29' },
  '405706071': { name: 'შპს შნო მოლი', debt: 5070, date: '2025-04-29' },
  '405451318': { name: 'შპს რესტორან ჯგუფი', debt: 600, date: '2025-04-29' },
  '406470563': { name: 'შპს ხინკა', debt: 0, date: '2025-04-29' },
  '34001000341': { name: 'მერაბი ბერიშვილი', debt: 345, date: '2025-04-29' },
  '406351068': { name: 'შპს სანაპირო 2022', debt: 0, date: '2025-04-29' },
  '405762045': { name: 'შპს ქეი-ბუ', debt: 0, date: '2025-04-29' },
  '405374107': { name: 'შპს ბიგ სემი', debt: 0, date: '2025-04-29' },
  '405598713': { name: 'შპს კატოსან', debt: 0, date: '2025-04-29' },
  '405404771': { name: 'შპს  ბრაუჰაუს ტიფლისი', debt: 0, date: '2025-04-29' },
  '405129999': { name: 'შპს ბუ-ჰუ', debt: 0, date: '2025-04-29' },
  '405488431': { name: 'შპს ათუ', debt: 0, date: '2025-04-29' },
  '405172094': { name: 'შპს გრინ თაუერი', debt: 0, date: '2025-04-29' },
  '404407879': { name: 'შპს გურმე', debt: 0, date: '2025-04-29' },
  '405535185': { name: 'შპს ქვევრი 2019', debt: 0, date: '2025-04-29' },
  '01008033976': { name: 'ლევან ადამია', debt: 0, date: '2025-04-29' },
  '01006019107': { name: 'გურანდა ლაღაძე', debt: 0, date: '2025-04-29' },
  '406256171': { name: 'შპს ნოვა იმპორტი', debt: 0, date: '2025-04-29' },
  '429322529': { name: 'შპს ტაიფუდი', debt: 0, date: '2025-04-29' },
  '405474311': { name: 'შპს კრაფტსიტი', debt: 0, date: '2025-04-29' },
  '01025015102': { name: 'გოგი სიდამონიძე', debt: 0, date: '2025-04-29' },
  '404699073': { name: 'შპს სენე გრუპი', debt: 0, date: '2025-04-29' },
  '406503145': { name: 'შპს სალობიე შარდენზე', debt: 0, date: '2025-04-29' },
  '402047236': { name: 'სს სტადიუმ ჰოტელ', debt: 0, date: '2025-04-29' },
  '01027041430': { name: 'მედეა გიორგობიანი', debt: 0, date: '2025-04-29' },
  '226109387': { name: 'სს ვილა პალასი ბაკურიანი', debt: 0, date: '2025-04-29' },
  '405460031': { name: 'შპს ბუ ხაო', debt: 3385, date: '2025-04-29' }
};

const API_URL = process.env.API_URL || 'http://localhost:3005';

async function populateInitialDebts() {
  // Convert to array format for bulk import
  const debtsArray = Object.entries(INITIAL_CUSTOMER_DEBTS).map(([customerId, data]) => ({
    customerId,
    name: data.name,
    debt: data.debt,
    date: data.date
  }));

  console.log(`Populating ${debtsArray.length} initial debts...`);

  try {
    const response = await fetch(`${API_URL}/api/config/debts/bulk`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(debtsArray)
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`HTTP ${response.status}: ${errorText}`);
    }

    const result = await response.json();
    console.log('✅ Success!');
    console.log(`Imported ${result.data?.length || 0} initial debts`);
    console.log(JSON.stringify(result, null, 2));
  } catch (error) {
    console.error('❌ Failed to populate initial debts:', error.message);
    process.exit(1);
  }
}

populateInitialDebts();
